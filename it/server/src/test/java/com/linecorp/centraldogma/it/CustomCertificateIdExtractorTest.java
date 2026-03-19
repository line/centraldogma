/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.centraldogma.it;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;

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

final class CustomCertificateIdExtractorTest {

    private static final String CLIENT_CN = "my-client";
    // TestCertificateIdExtractor prepends "test-" to the CN.
    private static final String CERT_ID = "test-" + CLIENT_CN;

    @Order(1)
    @RegisterExtension
    static final SelfSignedCertificateExtension serverCert = new SelfSignedCertificateExtension();

    @Order(2)
    @RegisterExtension
    static final SelfSignedCertificateExtension ca = new SelfSignedCertificateExtension();

    @Order(3)
    @RegisterExtension
    static final SignedCertificateExtension clientCert =
            new SignedCertificateExtension(CLIENT_CN, ca);

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.port(0, SessionProtocol.HTTPS);
            builder.tls(
                    new TlsConfig(serverCert.certificateFile(), serverCert.privateKeyFile(), null, null, null));
            builder.mtlsConfig(
                    new MtlsConfig(true, ImmutableList.of(ca.certificateFile())));
            builder.systemAdministrators(USERNAME);
        }

        @Override
        protected String accessToken() {
            final WebClient client = WebClient.builder("https://127.0.0.1:" + dogma.serverAddress().getPort())
                                              .factory(ClientFactory.insecure())
                                              .build();
            return getAccessToken(client, USERNAME, PASSWORD, "testId", true, true, false);
        }

        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            builder.factory(ClientFactory.insecure());
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

    @Test
    void mtlsWithCustomExtractor() {
        final WebClientBuilder builder =
                WebClient.builder("https://127.0.0.1:" + dogma.serverAddress().getPort());
        configureWebClient(builder);
        final WebClient mtlsClient = builder.build();

        final String contentPath = HttpApiV1Constants.PROJECTS_PREFIX + "/foo/repos/bar/contents/a.txt";

        // Not authorized yet — no app identity registered.
        AggregatedHttpResponse contentResponse = mtlsClient.get(contentPath).aggregate().join();
        assertThat(contentResponse.status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Register an app identity using the certificate ID produced by TestCertificateIdExtractor
        // which prepends "test-" to the CN.
        final AggregatedHttpResponse response =
                dogma.httpClient().post(API_V1_PATH_PREFIX + "appIdentities",
                                        QueryParams.of("appId", "cert1",
                                                       "type", "CERTIFICATE",
                                                       "certificateId", CERT_ID,
                                                       "isSystemAdmin", false),
                                        HttpData.empty()).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.contentUtf8()).contains("\"appId\":\"cert1\"");

        // Still forbidden — no project role granted yet.
        contentResponse = mtlsClient.get(contentPath).aggregate().join();
        assertThat(contentResponse.status()).isEqualTo(HttpStatus.FORBIDDEN);

        // Grant the cert1 app identity access to the 'foo' project.
        final HttpRequest request = HttpRequest.builder()
                                               .post("/api/v1/metadata/foo/appIdentities")
                                               .contentJson(new IdAndProjectRole("cert1", ProjectRole.MEMBER))
                                               .build();
        assertThat(dogma.httpClient().execute(request).aggregate().join().status()).isSameAs(HttpStatus.OK);

        // Now the mTLS client can access the content.
        contentResponse = mtlsClient.get(contentPath).aggregate().join();
        assertThat(contentResponse.status()).isEqualTo(HttpStatus.OK);
    }
}
