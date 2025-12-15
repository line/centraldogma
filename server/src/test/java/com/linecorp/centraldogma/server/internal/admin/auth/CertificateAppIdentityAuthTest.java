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
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientTlsConfig;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.SignedCertificateExtension;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.TlsConfig;
import com.linecorp.centraldogma.server.auth.MtlsConfig;
import com.linecorp.centraldogma.server.internal.api.MetadataApiService.IdAndProjectRole;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

final class CertificateAppIdentityAuthTest {

    private static final String CERT_ID = "centraldogma.com/my-client";

    @Order(1)
    @RegisterExtension
    static final SelfSignedCertificateExtension serverCert = new SelfSignedCertificateExtension();

    @Order(2)
    @RegisterExtension
    static final SelfSignedCertificateExtension ca = new SelfSignedCertificateExtension();

    @Order(3)
    @RegisterExtension
    static final SignedCertificateExtension clientCert =
            new SignedCertificateExtension("my-client", ca,
                                           ImmutableList.of("spiffe://" + CERT_ID));

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.port(0, SessionProtocol.HTTPS);
            builder.tls(
                    new TlsConfig(serverCert.certificateFile(), serverCert.privateKeyFile(), null, null, null));
            builder.mtlsConfig(
                    new MtlsConfig(true, ImmutableList.of("file:" + ca.certificateFile().getAbsolutePath())));
            builder.systemAdministrators(USERNAME);
        }

        @Override
        protected String accessToken() {
            final WebClient client = webClient(null);
            return getAccessToken(client, USERNAME, PASSWORD, "testId", true, true, false);
        }

        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            configureWebClient(builder);
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar").join();
            client.forRepo("foo", "bar").commit("Add a file", Change.ofTextUpsert("/a.txt", "hello"))
                  .push().join();
        }
    };

    private static void configureWebClient(WebClientBuilder builder) {
        final TlsKeyPair tlsKeyPair = TlsKeyPair.of(clientCert.privateKey(),
                                                    clientCert.certificate());
        final ClientTlsConfig tlsConfig =
                ClientTlsConfig.builder()
                               .tlsCustomizer(b -> b.trustManager(serverCert.certificate()))
                               .build();
        builder.factory(ClientFactory.builder()
                                     .tlsProvider(TlsProvider.of(tlsKeyPair),
                                                  tlsConfig)
                                     .build());
    }

    private static WebClient webClient(@Nullable String accessToken) {
        final TlsKeyPair tlsKeyPair = TlsKeyPair.of(clientCert.privateKey(),
                                                    clientCert.certificate());
        final ClientTlsConfig tlsConfig =
                ClientTlsConfig.builder()
                               .tlsCustomizer(b -> b.trustManager(serverCert.certificate()))
                               .build();
        final WebClientBuilder builder =
                WebClient.builder("https://127.0.0.1:" + dogma.serverAddress().getPort())
                         .factory(ClientFactory.builder()
                                               .tlsProvider(TlsProvider.of(tlsKeyPair),
                                                            tlsConfig)
                                               .build());
        if (accessToken != null) {
            builder.auth(AuthToken.ofOAuth2(accessToken));
        }
        return builder.build();
    }

    @Test
    void mtls() throws Exception {
        final WebClientBuilder builder =
                WebClient.builder("https://127.0.0.1:" + dogma.serverAddress().getPort());
        configureWebClient(builder);
        final WebClient mtlsClient = builder.build();

        final String contentPath = HttpApiV1Constants.PROJECTS_PREFIX + "/foo/repos/bar/contents/a.txt";

        AggregatedHttpResponse contentResponse = mtlsClient.get(contentPath).aggregate().join();
        assertThat(contentResponse.status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        final AggregatedHttpResponse response =
                dogma.httpClient().post(API_V1_PATH_PREFIX + "appIdentities",
                                        QueryParams.of("appId", "cert1",
                                                       "appIdentityType", "CERTIFICATE",
                                                       "certificateId", CERT_ID,
                                                       "isSystemAdmin", false),
                                        HttpData.empty()).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.contentUtf8()).contains("\"appId\":\"cert1\"");

        // It's now forbidden.
        contentResponse = mtlsClient.get(contentPath).aggregate().join();
        assertThat(contentResponse.status()).isEqualTo(HttpStatus.FORBIDDEN);

        final HttpRequest request = HttpRequest.builder()
                                               .post("/api/v1/metadata/foo/appIdentities")
                                               .contentJson(new IdAndProjectRole("cert1", ProjectRole.MEMBER))
                                               .build();
        // Grant the cert1 certification access to the 'foo' project.
        assertThat(dogma.httpClient().execute(request).aggregate().join().status()).isSameAs(HttpStatus.OK);

        contentResponse = mtlsClient.get(contentPath).aggregate().join();
        assertThat(contentResponse.status()).isEqualTo(HttpStatus.OK);
    }
}
