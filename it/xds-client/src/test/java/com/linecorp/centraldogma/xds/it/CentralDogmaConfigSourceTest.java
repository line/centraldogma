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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsResourceReader;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class CentralDogmaConfigSourceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("test").join();
            client.createRepository("test", "xds").join();
            client.forRepo("test", "xds")
                  .commit("Add cluster", CLUSTER_CHANGE)
                  .push()
                  .join();
        }
    };

    private static final String HCM_TYPE =
            "type.googleapis.com/envoy.extensions.filters.network" +
            ".http_connection_manager.v3.HttpConnectionManager";
    private static final String ROUTER_TYPE =
            "type.googleapis.com/envoy.extensions.filters.http.router.v3.Router";

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

    private static final Change<?> CLUSTER_CHANGE =
            Change.ofJsonUpsert("/clusters/my-cluster.json", CLUSTER_JSON);
    private static final Change<?> UPDATED_CLUSTER_CHANGE =
            Change.ofJsonUpsert("/clusters/my-cluster.json",
                                CLUSTER_JSON.replace("9999", "8888"));

    private static String clusterJson(String name, int port) {
        //language=JSON
        return """
                {
                  "name": "%s",
                  "type": "STATIC",
                  "load_assignment": {
                    "cluster_name": "%s",
                    "endpoints": [
                      {
                        "lb_endpoints": [
                          {
                            "endpoint": {
                              "address": {
                                "socket_address": {
                                  "address": "127.0.0.1",
                                  "port_value": %d
                                }
                              }
                            }
                          }
                        ]
                      }
                    ]
                  }
                }
                """.formatted(name, name, port);
    }

    private static String listenerJson(String ext) {
        //language=JSON
        return """
                {
                  "name": "test/xds/listeners/my-listener.%s",
                  "api_listener": {
                    "api_listener": {
                      "@type": "%s",
                      "stat_prefix": "http",
                      "rds": {
                        "route_config_name": "test/xds/routes/my-route.%s",
                        "config_source": { "self": {} }
                      },
                      "http_filters": [{
                        "name": "envoy.filters.http.router",
                        "typed_config": { "@type": "%s" }
                      }]
                    }
                  }
                }
                """.formatted(ext, HCM_TYPE, ext, ROUTER_TYPE);
    }

    private static String routeJson(String ext) {
        //language=JSON
        return """
                {
                  "name": "test/xds/routes/my-route.%s",
                  "virtual_hosts": [
                    {
                      "name": "local_service",
                      "domains": ["*"],
                      "routes": [
                        {
                          "match": { "prefix": "/" },
                          "route": { "cluster": "test/xds/clusters/my-cluster.%s" }
                        }
                      ]
                    }
                  ]
                }
                """.formatted(ext, ext);
    }

    private static String edsClusterJson(String ext) {
        //language=JSON
        return """
                {
                  "name": "test/xds/clusters/my-cluster.%s",
                  "type": "EDS",
                  "eds_cluster_config": {
                    "service_name": "test/xds/endpoints/my-cluster.%s",
                    "eds_config": {
                      "self": {}
                    }
                  }
                }
                """.formatted(ext, ext);
    }

    private static String endpointJson(String ext) {
        //language=JSON
        return """
                {
                  "cluster_name": "test/xds/endpoints/my-cluster.%s",
                  "endpoints": [
                    {
                      "lb_endpoints": [
                        {
                          "endpoint": {
                            "address": {
                              "socket_address": {
                                "address": "127.0.0.1",
                                "port_value": 7777
                              }
                            }
                          }
                        }
                      ]
                    }
                  ]
                }
                """.formatted(ext);
    }

    private static String bootstrapYaml() {
        final int port = dogma.serverAddress().getPort();
        //language=YAML
        return """
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
                dynamic_resources:
                  cds_config:
                    custom_config_source:
                      name: centraldogma.config_source
                      typed_config:
                        "@type": type.googleapis.com/com.linecorp.centraldogma\
                .xds.v1.CentralDogmaConfigSource
                        cluster_name: centraldogma-server
                  lds_config:
                    custom_config_source:
                      name: centraldogma.config_source
                      typed_config:
                        "@type": type.googleapis.com/com.linecorp.centraldogma\
                .xds.v1.CentralDogmaConfigSource
                        cluster_name: centraldogma-server
                """.formatted(port);
    }

    @Test
    void fetchClusterFromCentralDogma() {
        final Bootstrap bootstrap = XdsResourceReader.from(bootstrapYaml(), Bootstrap.class);
        final AtomicReference<ClusterSnapshot> snapshotRef = new AtomicReference<>();
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final SnapshotWatcher<Object> watcher = (snapshot, t) -> {
            if (t != null) {
                errorRef.set(t);
                return;
            }
            if (snapshot instanceof ClusterSnapshot) {
                snapshotRef.set((ClusterSnapshot) snapshot);
            }
        };

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .defaultSnapshotWatcher(watcher)
                                                     .build()) {
            xdsBootstrap.clusterRoot("test/xds/clusters/my-cluster.json");
            await().untilAsserted(() -> {
                assertThat(errorRef.get()).isNull();
                assertThat(snapshotRef.get()).isNotNull();
                assertThat(snapshotRef.get().xdsResource().resource().getName())
                        .isEqualTo("test/xds/clusters/my-cluster.json");
                assertThat(portValue(snapshotRef.get())).isEqualTo(9999);
            });

            // Push an update and verify the watcher emits the change.
            dogma.client().forRepo("test", "xds")
                 .commit("Update cluster", UPDATED_CLUSTER_CHANGE)
                 .push()
                 .join();
            await().untilAsserted(() -> {
                assertThat(errorRef.get()).isNull();
                assertThat(portValue(snapshotRef.get())).isEqualTo(8888);
            });
        }
    }

    @Test
    void clusterUpdateShouldBeReceivedAfterListenerSubscription() {
        // Push a separate cluster so this test doesn't interfere with others.
        final String clusterName = "test/xds/clusters/stable-cluster.json";
        dogma.client().forRepo("test", "xds")
             .commit("Add stable cluster",
                     Change.ofJsonUpsert("/clusters/stable-cluster.json",
                                         clusterJson(clusterName, 5555)),
                     Change.ofJsonUpsert("/listeners/standalone.json",
                                         listenerJson("json")))
             .push()
             .join();

        final Bootstrap bootstrap = XdsResourceReader.from(bootstrapYaml(), Bootstrap.class);
        final AtomicReference<ClusterSnapshot> clusterRef = new AtomicReference<>();
        final AtomicReference<ListenerSnapshot> listenerRef = new AtomicReference<>();
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final SnapshotWatcher<Object> watcher = (snapshot, t) -> {
            if (t != null) {
                errorRef.set(t);
                return;
            }
            if (snapshot instanceof ClusterSnapshot cs) {
                clusterRef.set(cs);
            } else if (snapshot instanceof ListenerSnapshot ls) {
                listenerRef.set(ls);
            }
        };

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .defaultSnapshotWatcher(watcher)
                                                     .build()) {
            // 1. Subscribe to cluster and wait for it to resolve.
            xdsBootstrap.clusterRoot(clusterName);
            await().untilAsserted(() -> {
                assertThat(errorRef.get()).isNull();
                assertThat(clusterRef.get()).isNotNull();
                assertThat(portValue(clusterRef.get())).isEqualTo(5555);
            });

            // 2. Subscribe to a listener — this publishes InterestedResources(LDS, ...)
            //    on the same config source (identical ConfigSource protobuf).
            //    If switchMapEager destroys the CDS watchers, the cluster update below
            //    will never be received.
            xdsBootstrap.listenerRoot("test/xds/listeners/standalone.json");

            // 3. Update the cluster and verify the watcher still receives the change.
            dogma.client().forRepo("test", "xds")
                 .commit("Update stable cluster",
                         Change.ofJsonUpsert("/clusters/stable-cluster.json",
                                             clusterJson(clusterName, 6666)))
                 .push()
                 .join();
            await().untilAsserted(() -> {
                assertThat(errorRef.get()).isNull();
                assertThat(portValue(clusterRef.get())).isEqualTo(6666);
            });
        }
    }

    static Stream<Arguments> fullResourceChainArgs() {
        return Stream.of(
                Arguments.of("json", (ChangeFactory) Change::ofJsonUpsert),
                Arguments.of("yaml", (ChangeFactory) Change::ofYamlUpsert)
        );
    }

    @ParameterizedTest
    @MethodSource("fullResourceChainArgs")
    void fullResourceChain(String ext, ChangeFactory factory) {
        dogma.client().forRepo("test", "xds")
             .commit("Add full chain resources (" + ext + ')',
                     factory.create("/listeners/my-listener." + ext, listenerJson(ext)),
                     factory.create("/routes/my-route." + ext, routeJson(ext)),
                     factory.create("/clusters/my-cluster." + ext, edsClusterJson(ext)),
                     factory.create("/endpoints/my-cluster." + ext, endpointJson(ext)))
             .push()
             .join();

        final Bootstrap bootstrap = XdsResourceReader.from(bootstrapYaml(), Bootstrap.class);
        final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .defaultSnapshotWatcher((snapshot, t) -> {
                                                         if (t != null) {
                                                             errorRef.set(t);
                                                             return;
                                                         }
                                                         if (snapshot instanceof ListenerSnapshot) {
                                                             snapshotRef.set((ListenerSnapshot) snapshot);
                                                         }
                                                     })
                                                     .build();
             ListenerRoot listenerRoot =
                     xdsBootstrap.listenerRoot("test/xds/listeners/my-listener." + ext)) {

            await().untilAsserted(() -> {
                assertThat(errorRef.get()).isNull();
                final ListenerSnapshot listenerSnapshot = snapshotRef.get();
                assertThat(listenerSnapshot).isNotNull();
                assertThat(listenerSnapshot.xdsResource().resource().getName())
                        .isEqualTo("test/xds/listeners/my-listener." + ext);

                assertThat(listenerSnapshot.routeSnapshot()).isNotNull();
                assertThat(listenerSnapshot.routeSnapshot().xdsResource().resource().getName())
                        .isEqualTo("test/xds/routes/my-route." + ext);

                final ClusterSnapshot clusterSnapshot =
                        listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                        .routeEntries().get(0).clusterSnapshot();
                assertThat(clusterSnapshot).isNotNull();
                assertThat(clusterSnapshot.xdsResource().resource().getName())
                        .isEqualTo("test/xds/clusters/my-cluster." + ext);

                assertThat(clusterSnapshot.endpointSnapshot()).isNotNull();
            });
        }
    }

    @Test
    void multipleClusters() {
        final int numClusters = 10;
        final int basePort = 7000;
        final Change<?>[] changes = new Change<?>[numClusters];
        for (int i = 0; i < numClusters; i++) {
            final String name = "test/xds/clusters/cluster-" + i + ".json";
            changes[i] = Change.ofJsonUpsert("/clusters/cluster-" + i + ".json",
                                             clusterJson(name, basePort + i));
        }
        dogma.client().forRepo("test", "xds")
             .commit("Add " + numClusters + " clusters", changes)
             .push()
             .join();

        final Bootstrap bootstrap = XdsResourceReader.from(bootstrapYaml(), Bootstrap.class);
        final Map<String, ClusterSnapshot> snapshots = new ConcurrentHashMap<>();
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final SnapshotWatcher<Object> watcher = (snapshot, t) -> {
            if (t != null) {
                errorRef.set(t);
                return;
            }
            if (snapshot instanceof ClusterSnapshot cs) {
                snapshots.put(cs.xdsResource().resource().getName(), cs);
            }
        };

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .defaultSnapshotWatcher(watcher)
                                                     .build()) {
            for (int i = 0; i < numClusters; i++) {
                xdsBootstrap.clusterRoot("test/xds/clusters/cluster-" + i + ".json");
            }

            await().untilAsserted(() -> {
                assertThat(errorRef.get()).isNull();
                for (int i = 0; i < numClusters; i++) {
                    final String name = "test/xds/clusters/cluster-" + i + ".json";
                    assertThat(snapshots).containsKey(name);
                    assertThat(portValue(snapshots.get(name))).isEqualTo(basePort + i);
                }
            });
        }
    }

    private static int portValue(ClusterSnapshot snapshot) {
        return snapshot.xdsResource().resource()
                       .getLoadAssignment()
                       .getEndpoints(0).getLbEndpoints(0)
                       .getEndpoint().getAddress().getSocketAddress()
                       .getPortValue();
    }

    @FunctionalInterface
    private interface ChangeFactory {
        Change<?> create(String path, String content);
    }
}
