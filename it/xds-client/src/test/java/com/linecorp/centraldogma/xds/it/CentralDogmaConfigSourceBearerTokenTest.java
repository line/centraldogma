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

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsResourceReader;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class CentralDogmaConfigSourceBearerTokenTest {

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

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.systemAdministrators(USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }

        @Override
        protected String accessToken() {
            return getAccessToken(
                    WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort()),
                    USERNAME, PASSWORD, true);
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

    @Test
    void fetchClusterWithBearerToken() {
        final String appToken = getAccessToken(dogma.httpClient(),
                                               USERNAME, PASSWORD, "xdsAppId", true);
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
                  secrets:
                    - name: centraldogma_token
                      generic_secret:
                        secret:
                          inline_string: "%s"
                dynamic_resources:
                  cds_config:
                    custom_config_source:
                      name: centraldogma.config_source
                      typed_config:
                        "@type": type.googleapis.com/com.linecorp.centraldogma\
                .xds.v1.CentralDogmaConfigSource
                        cluster_name: centraldogma-server
                        bearer_token_credential:
                          token_secret:
                            name: centraldogma_token
                """.formatted(port, appToken);

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
