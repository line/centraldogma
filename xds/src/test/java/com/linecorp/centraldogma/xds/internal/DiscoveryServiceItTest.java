/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.centraldogma.xds.internal;

import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.cluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createCluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createListener;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createRoute;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.httpConnectionManager;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.rdsConfigSource;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.routeConfiguration;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.updateRoute;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.google.protobuf.Any;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.SocketAddress.Protocol;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

@Testcontainers(disabledWithoutDocker = true)
final class DiscoveryServiceItTest {

    private static final String FOO_GROUP_NAME = "groups/foo";
    private static final String ECHO_CLUSTER = "groups/foo/clusters/echo-cluster";
    private static final String ECHO_CLUSTER_ADDRESS = "echo_upstream";
    private static final String NO_ECHO_CLUSTER = "groups/foo/clusters/no-echo-cluster";
    private static final String NO_ECHO_CLUSTER_ADDRESS = "no_echo_upstream";

    private static final String ECHO_ROUTE = "groups/foo/routes/echo-route";
    private static final String ECHO_LISTENER = "groups/foo/listeners/echo-listener";

    // Using 10000 is fine because this port is used in the container. The port is exposed with a different
    // port number which you can get via ContainerState.getMappedPort().
    private static final Integer ENVOY_LISTENER_PORT = 10000;

    @RegisterExtension
    final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final EchoContainer echoCluster = new EchoContainer(true)
            .withNetwork(NETWORK)
            .withNetworkAliases(ECHO_CLUSTER_ADDRESS);

    @Container
    private static final EchoContainer noEchoCluster = new EchoContainer(false)
            .withNetwork(NETWORK)
            .withNetworkAliases(NO_ECHO_CLUSTER_ADDRESS);

    @BeforeEach
    void setUp() throws IOException {
        createGroup(FOO_GROUP_NAME, dogma.httpClient());
        final Cluster echoCluster = echoCluster(true);
        createCluster(FOO_GROUP_NAME, "echo-cluster", echoCluster, dogma.httpClient());
        final Cluster noEchoCluster = echoCluster(false);
        createCluster(FOO_GROUP_NAME, "no-echo-cluster", noEchoCluster, dogma.httpClient());
        final RouteConfiguration route = createEchoRoute(true);
        createRoute(FOO_GROUP_NAME, "echo-route", route, dogma.httpClient());
        final Listener listener = createEchoListener();
        createListener(FOO_GROUP_NAME, "echo-listener", listener, dogma.httpClient());
    }

    @AfterAll
    static void afterAll() {
        NETWORK.close();
    }

    @CsvSource({ "envoy/xds.config.yaml", "envoy/ads.config.yaml" })
    @ParameterizedTest
    void validateTestRequestToEchoClusterViaEnvoy(String configFile) throws Exception {
        org.testcontainers.Testcontainers.exposeHostPorts(dogma.serverAddress().getPort());
        try (EnvoyContainer envoy =
                     new EnvoyContainer(configFile, () -> dogma.serverAddress().getPort())
                             .withExposedPorts(ENVOY_LISTENER_PORT)
                             .withNetwork(NETWORK)) {
            envoy.start();

            final String uri = "http://" + envoy.getHost() + ':' + envoy.getMappedPort(ENVOY_LISTENER_PORT);
            final AggregatedHttpResponse res = WebClient.of(uri)
                                                        .post("/", "Hello!")
                                                        .aggregate().join();
            assertThat(res.headers().status()).isSameAs(HttpStatus.OK);
            assertThat(res.contentUtf8()).contains("\"body\": \"Hello!\"");

            // Change the route to no-echo-cluster.
            final RouteConfiguration route = createEchoRoute(false);
            updateRoute(FOO_GROUP_NAME, "echo-route", route, dogma.httpClient());
            await().atMost(5, TimeUnit.SECONDS)
                   .ignoreExceptions()
                   .untilAsserted(() -> {
                       final AggregatedHttpResponse res2 = WebClient.of(uri)
                                                                    .post("/", "Hello!")
                                                                    .aggregate().join();
                       assertThat(res2.headers().status()).isSameAs(HttpStatus.OK);
                       assertThat(res2.content().isEmpty()).isTrue();
                   });
            // Envoy closes the connection so a ClosedSessionException is raised in the Server.
            envoy.stop();
        }
    }

    private static Cluster echoCluster(boolean echo) {
        final String clusterName = echo ? ECHO_CLUSTER : NO_ECHO_CLUSTER;
        final ClusterLoadAssignment loadAssignment =
                XdsTestUtil.loadAssignment(clusterName, echo ? ECHO_CLUSTER_ADDRESS : NO_ECHO_CLUSTER_ADDRESS,
                                           echo ? EchoContainer.PORT : EchoContainer.NO_ECHO_PORT);
        return cluster(clusterName, loadAssignment, DiscoveryType.STRICT_DNS);
    }

    private static RouteConfiguration createEchoRoute(boolean echo) {
        return routeConfiguration(ECHO_ROUTE, echo ? ECHO_CLUSTER : NO_ECHO_CLUSTER);
    }

    private static Listener createEchoListener() {
        final HttpConnectionManager manager = httpConnectionManager(ECHO_ROUTE, rdsConfigSource());
        return Listener.newBuilder()
                       .setName(ECHO_LISTENER)
                       .setAddress(Address.newBuilder()
                                          .setSocketAddress(SocketAddress.newBuilder()
                                                                         .setAddress("0.0.0.0")
                                                                         .setPortValue(ENVOY_LISTENER_PORT)
                                                                         .setProtocol(Protocol.TCP)))
                       .addFilterChains(
                               FilterChain.newBuilder()
                                          .addFilters(Filter.newBuilder()
                                                            .setName("envoy.http_connection_manager")
                                                            .setTypedConfig(Any.pack(manager))))
                       .build();
    }
}
