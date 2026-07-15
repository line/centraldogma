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
package com.linecorp.centraldogma.xds.it;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientTlsConfig;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.SignedCertificateExtension;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsResourceReader;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.TlsConfig;
import com.linecorp.centraldogma.server.auth.MtlsConfig;
import com.linecorp.centraldogma.server.internal.api.MetadataApiService.IdAndProjectRole;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class CentralDogmaConfigSourceMtlsTest {

    private static final String CERT_ID = "my-client";

    @Order(1)
    @RegisterExtension
    static final SelfSignedCertificateExtension serverCert = new SelfSignedCertificateExtension();

    @Order(2)
    @RegisterExtension
    static final SelfSignedCertificateExtension ca = new SelfSignedCertificateExtension();

    @Order(3)
    @RegisterExtension
    static final SignedCertificateExtension clientCert =
            new SignedCertificateExtension(CERT_ID, ca, false);

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.port(0, SessionProtocol.HTTPS);
            builder.tls(new TlsConfig(serverCert.certificateFile(), serverCert.privateKeyFile(),
                                      null, null, null));
            builder.mtlsConfig(new MtlsConfig(true, ImmutableList.of(ca.certificateFile())));
            builder.systemAdministrators(USERNAME);
        }

        @Override
        protected String accessToken() {
            final WebClient client = webClient();
            return getAccessToken(client, USERNAME, PASSWORD, "testId", true, true, false);
        }

        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            configureWebClientBuilder(builder);
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("test").join();
            client.createRepository("test", "xds").join();
            client.forRepo("test", "xds")
                  .commit("Add cluster",
                          Change.ofJsonUpsert("/clusters/my-cluster.json", CLUSTER_JSON))
                  .push()
                  .join();
        }
    };

    //language=JSON
    private static final String CLUSTER_JSON = """
            {
              "name": "test/xds/clusters/my-cluster.json",
              "type": "STATIC",
              "load_assignment": {
                "cluster_name": "test/xds/clusters/my-cluster.json",
                "endpoints": [
                  {
                    "lb_endpoints": [
                      {
                        "endpoint": {
                          "address": {
                            "socket_address": {
                              "address": "127.0.0.1",
                              "port_value": 9999
                            }
                          }
                        }
                      }
                    ]
                  }
                ]
              }
            }
            """;

    private static void configureWebClientBuilder(WebClientBuilder builder) {
        final TlsKeyPair tlsKeyPair = TlsKeyPair.of(clientCert.privateKey(),
                                                    clientCert.certificate());
        final ClientTlsConfig tlsConfig =
                ClientTlsConfig.builder()
                               .tlsCustomizer(b -> b.trustManager(serverCert.certificate()))
                               .build();
        builder.factory(ClientFactory.builder()
                                     .tlsProvider(TlsProvider.of(tlsKeyPair), tlsConfig)
                                     .build());
    }

    private static WebClient webClient() {
        final TlsKeyPair tlsKeyPair = TlsKeyPair.of(clientCert.privateKey(),
                                                    clientCert.certificate());
        final ClientTlsConfig tlsConfig =
                ClientTlsConfig.builder()
                               .tlsCustomizer(b -> b.trustManager(serverCert.certificate()))
                               .build();
        return WebClient.builder("https://127.0.0.1:" + dogma.serverAddress().getPort())
                        .factory(ClientFactory.builder()
                                              .tlsProvider(TlsProvider.of(tlsKeyPair), tlsConfig)
                                              .build())
                        .build();
    }

    @Test
    void fetchClusterWithMtls() throws Exception {
        // Register the client certificate as an app identity.
        assertThat(dogma.httpClient()
                        .post(API_V1_PATH_PREFIX + "appIdentities",
                              QueryParams.of("appId", "cert1",
                                             "type", "CERTIFICATE",
                                             "certificateId", CERT_ID,
                                             "isSystemAdmin", false),
                              HttpData.empty())
                        .aggregate().join().status())
                .isEqualTo(HttpStatus.CREATED);

        // Grant the cert identity access to the 'test' project.
        final HttpRequest grantRequest = HttpRequest.builder()
                                                    .post("/api/v1/metadata/test/appIdentities")
                                                    .contentJson(new IdAndProjectRole("cert1",
                                                                                      ProjectRole.MEMBER))
                                                    .build();
        assertThat(dogma.httpClient().execute(grantRequest).aggregate().join().status())
                .isSameAs(HttpStatus.OK);

        final int port = dogma.serverAddress().getPort();
        //language=YAML
        final String yaml = """
                static_resources:
                  clusters:
                    - name: centraldogma-server
                      type: STATIC
                      load_assignment:
                        cluster_name: centraldogma-server
                        endpoints:
                          - lb_endpoints:
                              - endpoint:
                                  address:
                                    socket_address:
                                      address: 127.0.0.1
                                      port_value: %d
                      transport_socket:
                        name: envoy.transport_sockets.tls
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions\
                .transport_sockets.tls.v3.UpstreamTlsContext
                          common_tls_context:
                            tls_certificates:
                              - certificate_chain:
                                  filename: %s
                                private_key:
                                  filename: %s
                            validation_context:
                              trusted_ca:
                                filename: %s
                dynamic_resources:
                  cds_config:
                    custom_config_source:
                      name: centraldogma.config_source
                      typed_config:
                        "@type": type.googleapis.com/com.linecorp.centraldogma\
                .xds.v1.CentralDogmaConfigSource
                        cluster_name: centraldogma-server
                """.formatted(port,
                              clientCert.certificateFile().getAbsolutePath(),
                              clientCert.privateKeyFile().getAbsolutePath(),
                              serverCert.certificateFile().getAbsolutePath());

        final Bootstrap bootstrap = XdsResourceReader.from(yaml, Bootstrap.class);
        final AtomicReference<ClusterSnapshot> snapshotRef = new AtomicReference<>();
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .defaultSnapshotWatcher((snapshot, t) -> {
                                                         if (t != null) {
                                                             errorRef.set(t);
                                                             return;
                                                         }
                                                         if (snapshot instanceof ClusterSnapshot) {
                                                             snapshotRef.set((ClusterSnapshot) snapshot);
                                                         }
                                                     })
                                                     .build()) {
            xdsBootstrap.clusterRoot("test/xds/clusters/my-cluster.json");
            await().untilAsserted(() -> {
                assertThat(errorRef.get()).isNull();
                assertThat(snapshotRef.get()).isNotNull();
                assertThat(snapshotRef.get().xdsResource().resource().getName())
                        .isEqualTo("test/xds/clusters/my-cluster.json");
                assertThat(snapshotRef.get().xdsResource().resource()
                                      .getLoadAssignment()
                                      .getEndpoints(0).getLbEndpoints(0)
                                      .getEndpoint().getAddress().getSocketAddress()
                                      .getPortValue())
                        .isEqualTo(9999);
            });
        }
    }
}
