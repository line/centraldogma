/*
 * Copyright 2025 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.centraldogma.server.internal.admin.auth;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.csrfTokenFromSignedJwt;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.decryptAndGetSignedJwt;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.sessionCookieName;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getSessionCookie;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.login;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.logout;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.nimbusds.jose.JWEDecrypter;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jwt.SignedJWT;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.EncryptionAtRestConfig;
import com.linecorp.centraldogma.server.auth.SessionKey;
import com.linecorp.centraldogma.server.internal.api.sysadmin.UpdateServerStatusRequest;
import com.linecorp.centraldogma.server.internal.api.sysadmin.UpdateServerStatusRequest.Scope;
import com.linecorp.centraldogma.server.management.ServerStatus;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

final class AuthServerTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.systemAdministrators(USERNAME);
        }
    };

    @RegisterExtension
    static final CentralDogmaExtension tlsEnabledAndEncryptCookie = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.port(0, SessionProtocol.HTTPS);
            builder.encryptionAtRest(new EncryptionAtRestConfig(true, true));
            builder.systemAdministrators(USERNAME);
        }
    };

    @RegisterExtension
    static final CentralDogmaExtension readOnlyDogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.port(0, SessionProtocol.HTTPS);
            builder.encryptionAtRest(new EncryptionAtRestConfig(true, true));
            builder.systemAdministrators(USERNAME);
        }

        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            builder.factory(ClientFactory.insecure());
        }

        @Override
        protected String accessToken() {
            return getAccessToken(
                    WebClient.builder("https://127.0.0.1:" + readOnlyDogma.serverAddress().getPort())
                             .factory(ClientFactory.insecure())
                             .build(),
                    USERNAME, PASSWORD, "testId", true, true, true);
        }
    };

    private final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @BeforeAll
    static void setUp() {
        readOnlyDogma.httpClient().prepare()
                     .put(API_V1_PATH_PREFIX + "status")
                     .contentJson(new UpdateServerStatusRequest(ServerStatus.READ_ONLY, Scope.ALL))
                     .execute().aggregate().join();
    }

    @Test
    void testLogin() throws Exception {
        AggregatedHttpResponse httpResponse = login(dogma.httpClient(), USERNAME, PASSWORD);
        Cookie sessionCookie = validate(httpResponse, false, false);
        // e.g. ec945f23-3f76-4d18-ae8a-6fd537c4262e
        assertThat(sessionCookie.value().length()).isEqualTo(36);
        String expectedCsrfToken = "csrfToken";
        validateCsrfToken(httpResponse, expectedCsrfToken);
        userServiceReturnedCsrfToken(dogma.httpClient(), sessionCookie, expectedCsrfToken);
        validateLogout(dogma.httpClient(), sessionCookie, expectedCsrfToken);

        // tlsEnabledAndEncryptCookie
        WebClient insecureClient = insecureClient(tlsEnabledAndEncryptCookie);
        httpResponse = login(insecureClient, USERNAME, PASSWORD);
        sessionCookie = validate(httpResponse, true, true);
        SessionKey sessionKey = tlsEnabledAndEncryptCookie.dogma().encryptionStorageManager()
                                                          .getCurrentSessionKey().join();
        JWEDecrypter decrypter = new DirectDecrypter(sessionKey.encryptionKey());
        SignedJWT signedJwt = decryptAndGetSignedJwt(ctx, decrypter, sessionCookie.value());
        assertThat(signedJwt.getJWTClaimsSet().getSubject()).isEqualTo(USERNAME);
        assertThat(signedJwt.getJWTClaimsSet().getClaim("sessionId").toString().length()).isEqualTo(36);
        validateCsrfToken(httpResponse, expectedCsrfToken);
        userServiceReturnedCsrfToken(insecureClient, sessionCookie, expectedCsrfToken);
        validateLogout(insecureClient, sessionCookie, expectedCsrfToken);

        // readOnlyDogma
        insecureClient = insecureClient(readOnlyDogma);
        httpResponse = login(insecureClient, USERNAME, PASSWORD);
        sessionCookie = validate(httpResponse, true, true);
        sessionKey = readOnlyDogma.dogma().encryptionStorageManager()
                                  .getCurrentSessionKey().join();
        decrypter = new DirectDecrypter(sessionKey.encryptionKey());
        signedJwt = decryptAndGetSignedJwt(ctx, decrypter, sessionCookie.value());
        assertThat(signedJwt.getJWTClaimsSet().getSubject()).isEqualTo(USERNAME);
        assertThat(signedJwt.getJWTClaimsSet().getClaim("sessionId")).isNull();

        expectedCsrfToken = csrfTokenFromSignedJwt(signedJwt);
        assertThat(expectedCsrfToken.length()).isEqualTo(64); //sha256 / 4 = 64
        validateCsrfToken(httpResponse, expectedCsrfToken);
        userServiceReturnedCsrfToken(insecureClient, sessionCookie, expectedCsrfToken);
        validateLogout(insecureClient, sessionCookie, expectedCsrfToken);
    }

    private static void validateLogout(WebClient webClient, Cookie sessionCookie, String expectedCsrfToken) {
        assertThat(logout(webClient, sessionCookie, expectedCsrfToken).status()).isSameAs(HttpStatus.OK);
    }

    private static void userServiceReturnedCsrfToken(WebClient webClient, Cookie sessionCookie,
                                                     String expectedCsrfToken)
            throws InterruptedException, ExecutionException {
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.GET, "/api/v0/users/me")
                                                     .cookie(sessionCookie)
                                                     .build();
        final AggregatedHttpResponse httpResponse = webClient.execute(headers).aggregate().get();
        assertThat(httpResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(httpResponse.headers().get(SessionUtil.X_CSRF_TOKEN)).isEqualTo(expectedCsrfToken);
    }

    private static void validateCsrfToken(AggregatedHttpResponse httpResponse, String expectedCsrfToken)
            throws JsonParseException {
        assertThat(Jackson.readTree(httpResponse.contentUtf8()).get("csrf_token").asText())
                .isEqualTo(expectedCsrfToken);
    }

    private static WebClient insecureClient(CentralDogmaExtension dogma) {
        return WebClient.builder("https://127.0.0.1:" + dogma.serverAddress().getPort())
                        .factory(ClientFactory.insecure())
                        .build();
    }

    private static Cookie validate(AggregatedHttpResponse httpResponse, boolean tlsEnabled,
                                    boolean encryptSessionCookie) {
        assertThat(httpResponse.status()).isEqualTo(HttpStatus.OK);
        final Cookie sessionCookie = getSessionCookie(httpResponse, tlsEnabled, encryptSessionCookie);
        assertThat(sessionCookie.name()).isEqualTo(sessionCookieName(tlsEnabled, encryptSessionCookie));
        return sessionCookie;
    }
}
