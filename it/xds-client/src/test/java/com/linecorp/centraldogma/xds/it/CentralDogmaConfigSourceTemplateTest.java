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
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsResourceReader;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;

class CentralDogmaConfigSourceTemplateTest {

    // FreeMarker snippet that constructs the full xDS resource name from centraldogma context.
    // Includes the file extension and ?profile= when present.
    private static final String RESOURCE_NAME =
            "${centraldogma.project}/${centraldogma.repo}${centraldogma.path}" +
            "<#if centraldogma.variableFile??>?profile=${centraldogma.variableFile}</#if>";

    // An EDS cluster template — name, type, and edsClusterConfig are set automatically.
    // Any additional cluster fields (e.g., transportSocket) can be specified directly in vars.
    private static final String EDS_CLUSTER_JSON_TEMPLATE = """
            {
              "name": "%s",
              "type": "EDS",
              "eds_cluster_config": {
                "service_name": "${vars.service_name}",
                "eds_config": {
                  "custom_config_source": {
                    "@type": "type.googleapis.com/com.github.xds\
            .centraldogma.v1.CentralDogmaConfigSource",
                    "cluster_name": "centraldogma-server"
                  }
                }
              }<#list vars as key, value><#if key != "service_name">,
              "${key}": ${toJson(value)}</#if></#list>
            }
            """.formatted(RESOURCE_NAME);

    // A listener template — each route object is serialized transparently via ${toJson(r)},
    // so any field on config.route.v3.Route is supported without template changes.
    private static final String LISTENER_JSON_TEMPLATE = """
            {
              "name": "%1$s",
              "api_listener": {
                "api_listener": {
                  "@type": "type.googleapis.com/envoy.extensions.filters.network\
            .http_connection_manager.v3.HttpConnectionManager",
                  "stat_prefix": "http",
                  "route_config": {
                    "name": "%1$s",
                    "virtual_hosts": [{
                      "name": "default",
                      "domains": ["*"],
                      "routes": [
                        <#list vars.routes as r>
                        ${toJson(r)}<#if r?has_next>,</#if>
                        </#list>
                      ]
                    }]
                  },
                  "http_filters": [{
                    "name": "envoy.filters.http.router",
                    "typed_config": {
                      "@type": "type.googleapis.com/envoy.extensions.filters.http\
            .router.v3.Router"
                    }
                  }]
                }
              }
            }
            """.formatted(RESOURCE_NAME);

    // YAML variant of the EDS cluster template.
    private static final String EDS_CLUSTER_YAML_TEMPLATE = """
            name: %s
            type: EDS
            eds_cluster_config:
              service_name: ${vars.service_name}
              eds_config:
                custom_config_source:
                  "@type": type.googleapis.com/com.github.xds\
            .centraldogma.v1.CentralDogmaConfigSource
                  cluster_name: centraldogma-server
            <#list vars as key, value><#if key != "service_name">
            ${key}: ${toJson(value)}
            </#if></#list>
            """.formatted(RESOURCE_NAME);

    // YAML variant of the listener template.
    private static final String LISTENER_YAML_TEMPLATE = """
            name: %1$s
            api_listener:
              api_listener:
                "@type": type.googleapis.com/envoy.extensions.filters.network\
            .http_connection_manager.v3.HttpConnectionManager
                stat_prefix: http
                route_config:
                  name: %1$s
                  virtual_hosts:
                    - name: default
                      domains:
                        - "*"
                      routes:
            <#list vars.routes as r>
                        - ${toJson(r)}
            </#list>
                http_filters:
                  - name: envoy.filters.http.router
                    typed_config:
                      "@type": type.googleapis.com/envoy.extensions.filters.http\
            .router.v3.Router
            """.formatted(RESOURCE_NAME);

    //language=JSON
    private static final String ENDPOINT_JSON = """
            {
              "cluster_name": "test/xds/endpoints/my-endpoint.json",
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
            """;

    // Profile that provides the serviceName variable for the EDS cluster template.
    private static final String EDS_CONFIG_PROFILE = """
            service_name: test/xds/endpoints/my-endpoint.json
            connect_timeout: 5s
            """;

    private static final String EDS_CONFIG_PROFILE_PATH = "/xds/eds-config.yaml";

    private static String clusterResourceName(String ext) {
        return "test/xds/clusters/default-outbound." + ext +
               ".ftl?profile=" + EDS_CONFIG_PROFILE_PATH;
    }

    private static String routesYaml(String clusterExt, int routeCount) {
        final String cluster = clusterResourceName(clusterExt);
        if (routeCount == 2) {
            return """
                    routes:
                      - match:
                          prefix: /api
                          headers:
                            - name: x-api-version
                              string_match:
                                exact: v2
                        route:
                          cluster: %1$s
                      - match:
                          prefix: /
                        route:
                          cluster: %1$s
                    """.formatted(cluster);
        }
        return """
                routes:
                  - match:
                      prefix: /
                    route:
                      cluster: %s
                """.formatted(cluster);
    }

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("test").join();
            client.createRepository("test", "xds").join();
        }
    };

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
                      "@type": type.googleapis.com/com.github.xds\
                .centraldogma.v1.CentralDogmaConfigSource
                      cluster_name: centraldogma-server
                  lds_config:
                    custom_config_source:
                      "@type": type.googleapis.com/com.github.xds\
                .centraldogma.v1.CentralDogmaConfigSource
                      cluster_name: centraldogma-server
                """.formatted(port);
    }

    static Stream<Arguments> clusterTemplateArgs() {
        return Stream.of(
                Arguments.of("json", EDS_CLUSTER_JSON_TEMPLATE),
                Arguments.of("yaml", EDS_CLUSTER_YAML_TEMPLATE)
        );
    }

    @ParameterizedTest
    @MethodSource("clusterTemplateArgs")
    void templateCluster(String ext, String clusterTemplate) {
        dogma.client().forRepo("test", "xds")
             .commit("Add " + ext + " cluster template and endpoint",
                     Change.ofTextUpsert("/clusters/default-outbound." + ext + ".ftl", clusterTemplate),
                     Change.ofJsonUpsert("/endpoints/my-endpoint.json", ENDPOINT_JSON),
                     Change.ofTextUpsert(EDS_CONFIG_PROFILE_PATH, EDS_CONFIG_PROFILE))
             .push()
             .join();

        final String clusterName = clusterResourceName(ext);
        final Bootstrap bootstrap = XdsResourceReader.from(bootstrapYaml(), Bootstrap.class);
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
            xdsBootstrap.clusterRoot(clusterName);
            await().untilAsserted(() -> {
                assertThat(errorRef.get()).isNull();
                final ClusterSnapshot cs = snapshotRef.get();
                assertThat(cs).isNotNull();
                final Cluster cluster = cs.xdsResource().resource();
                assertThat(cluster.getName()).isEqualTo(clusterName);
                assertThat(cluster.getType()).isEqualTo(Cluster.DiscoveryType.EDS);
                assertThat(cluster.getEdsClusterConfig().getServiceName())
                        .isEqualTo("test/xds/endpoints/my-endpoint.json");
                assertThat(cluster.getConnectTimeout().getSeconds()).isEqualTo(5);
                assertThat(cs.endpointSnapshot()).isNotNull();
                assertThat(endpointPort(cs)).isEqualTo(7777);
            });

            // Push an updated endpoint and verify the watcher picks up the change.
            dogma.client().forRepo("test", "xds")
                 .commit("Update endpoint port",
                         Change.ofJsonUpsert("/endpoints/my-endpoint.json",
                                             ENDPOINT_JSON.replace("7777", "6666")))
                 .push()
                 .join();
            await().untilAsserted(() -> {
                assertThat(errorRef.get()).isNull();
                assertThat(endpointPort(snapshotRef.get())).isEqualTo(6666);
            });
        }
    }

    private static int endpointPort(ClusterSnapshot cs) {
        return cs.endpointSnapshot().xdsResource().resource()
                 .getEndpoints(0).getLbEndpoints(0)
                 .getEndpoint().getAddress().getSocketAddress()
                 .getPortValue();
    }

    static Stream<Arguments> listenerTemplateArgs() {
        return Stream.of(
                Arguments.of("json", LISTENER_JSON_TEMPLATE, EDS_CLUSTER_JSON_TEMPLATE,
                              "/xds/two-routes.yaml", 2),
                Arguments.of("json", LISTENER_JSON_TEMPLATE, EDS_CLUSTER_JSON_TEMPLATE,
                              "/xds/single-route.yaml", 1),
                Arguments.of("yaml", LISTENER_YAML_TEMPLATE, EDS_CLUSTER_YAML_TEMPLATE,
                              "/xds/two-routes-yaml.yaml", 2),
                Arguments.of("yaml", LISTENER_YAML_TEMPLATE, EDS_CLUSTER_YAML_TEMPLATE,
                              "/xds/single-route-yaml.yaml", 1)
        );
    }

    @ParameterizedTest
    @MethodSource("listenerTemplateArgs")
    void templateListener(String ext, String listenerTemplate, String clusterTemplate,
                           String profilePath, int expectedRouteCount) {
        final String profileContent = routesYaml(ext, expectedRouteCount);
        dogma.client().forRepo("test", "xds")
             .commit("Add " + ext + " listener template with profile " + profilePath,
                     Change.ofTextUpsert("/listeners/default-outbound." + ext + ".ftl",
                                         listenerTemplate),
                     Change.ofTextUpsert("/clusters/default-outbound." + ext + ".ftl", clusterTemplate),
                     Change.ofJsonUpsert("/endpoints/my-endpoint.json", ENDPOINT_JSON),
                     Change.ofTextUpsert(EDS_CONFIG_PROFILE_PATH, EDS_CONFIG_PROFILE),
                     Change.ofTextUpsert(profilePath, profileContent))
             .push()
             .join();

        final String resourceName =
                "test/xds/listeners/default-outbound." + ext + ".ftl?profile=" + profilePath;

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
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(resourceName)) {

            await().untilAsserted(() -> {
                assertThat(errorRef.get()).isNull();
                final ListenerSnapshot ls = snapshotRef.get();
                assertThat(ls).isNotNull();
                assertThat(ls.xdsResource().resource().getName()).isEqualTo(resourceName);

                assertThat(ls.routeSnapshot()).isNotNull();
                assertThat(ls.routeSnapshot().xdsResource().resource()
                             .getVirtualHosts(0).getRoutesCount())
                        .isEqualTo(expectedRouteCount);

                final ClusterSnapshot clusterSnapshot =
                        ls.routeSnapshot().virtualHostSnapshots().get(0)
                          .routeEntries().get(0).clusterSnapshot();
                assertThat(clusterSnapshot).isNotNull();
                assertThat(clusterSnapshot.xdsResource().resource().getName())
                        .isEqualTo(clusterResourceName(ext));
                assertThat(clusterSnapshot.endpointSnapshot()).isNotNull();
            });
        }
    }

    @Test
    void camelCaseTemplateWithSnakeCaseProfile() {
        // Template uses camelCase for protobuf fields,
        // but the profile values use snake_case via toJson.
        final String template = """
                {
                  "name": "%s",
                  "type": "STATIC",
                  "loadAssignment": ${toJson(vars.load_assignment)}
                }
                """.formatted(RESOURCE_NAME);
        final String profile = """
                load_assignment:
                  cluster_name: test/xds/clusters/mixed-case.json.ftl?profile=/xds/mixed-case.yaml
                  endpoints:
                    - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: 4444
                """;

        dogma.client().forRepo("test", "xds")
             .commit("Add mixed-case cluster",
                     Change.ofTextUpsert("/clusters/mixed-case.json.ftl", template),
                     Change.ofTextUpsert("/xds/mixed-case.yaml", profile))
             .push()
             .join();

        final String clusterName = "test/xds/clusters/mixed-case.json.ftl?profile=/xds/mixed-case.yaml";
        final Bootstrap bootstrap = XdsResourceReader.from(bootstrapYaml(), Bootstrap.class);
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
            xdsBootstrap.clusterRoot(clusterName);
            await().untilAsserted(() -> {
                assertThat(errorRef.get()).isNull();
                final ClusterSnapshot cs = snapshotRef.get();
                assertThat(cs).isNotNull();
                final Cluster cluster = cs.xdsResource().resource();
                assertThat(cluster.getName()).isEqualTo(clusterName);
                assertThat(cluster.getLoadAssignment()
                                  .getEndpoints(0).getLbEndpoints(0)
                                  .getEndpoint().getAddress().getSocketAddress()
                                  .getPortValue())
                        .isEqualTo(4444);
            });
        }
    }
}
