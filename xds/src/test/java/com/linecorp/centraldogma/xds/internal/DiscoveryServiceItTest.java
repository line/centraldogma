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

import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.CLUSTER_FILE;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.CLUSTER_REPO;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.CONTROL_PLANE_PROJECT;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.LISTENER_FILE;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.LISTENER_REPO;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.ROUTE_FILE;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.ROUTE_REPO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.GrpcService.EnvoyGrpc;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.SocketAddress.Protocol;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager.CodecType;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;

@Testcontainers(disabledWithoutDocker = true)
final class DiscoveryServiceItTest {

    private static final String API_CONFIG_SOURCE_CLUSTER_NAME = "xds_cluster";

    private static final String ECHO_CLUSTER = "echo_cluster";
    private static final String ECHO_CLUSTER_ADDRESS = "echo_upstream";
    private static final String NO_ECHO_CLUSTER = "no_echo_cluster";
    private static final String NO_ECHO_CLUSTER_ADDRESS = "no_echo_upstream";

    private static final String ECHO_ROUTE = "echo_route";
    private static final String ECHO_LISTENER = "echo_listener";

    // Using 10000 is fine because this port is used in the container. The port is exposed with a different
    // port number which you can get via ContainerState.getMappedPort().
    private static final Integer ENVOY_LISTENER_PORT = 10000;

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            final Cluster echoCluster = createEchoCluster(true);
            commit(client, echoCluster, CLUSTER_REPO, ECHO_CLUSTER, CLUSTER_FILE);
            final Cluster noEchoCluster = createEchoCluster(false);
            commit(client, noEchoCluster, CLUSTER_REPO, NO_ECHO_CLUSTER, CLUSTER_FILE);
            final RouteConfiguration route = createEchoRoute(true);
            commit(client, route, ROUTE_REPO, ECHO_ROUTE, ROUTE_FILE);
            final Listener listener = createEchoListener();
            commit(client, listener, LISTENER_REPO, ECHO_LISTENER, LISTENER_FILE);
        }

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

    @AfterAll
    static void afterAll() {
        NETWORK.close();
    }

    @CsvSource({ "envoy/xds.config.yaml", "envoy/ads.config.yaml" })
    @ParameterizedTest
    void validateTestRequestToEchoClusterViaEnvoy(String configFile) throws InterruptedException {
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

            // Change the route to noEchoCluster.
            final RouteConfiguration route = createEchoRoute(false);
            commit(dogma.client(), route, ROUTE_REPO, ECHO_ROUTE, ROUTE_FILE);

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

    private static Cluster createEchoCluster(boolean echo) {
        final SocketAddress.Builder socketAddress = SocketAddress
                .newBuilder()
                .setAddress(echo ? ECHO_CLUSTER_ADDRESS : NO_ECHO_CLUSTER_ADDRESS)
                .setPortValue(echo ? EchoContainer.PORT : EchoContainer.NO_ECHO_PORT)
                .setProtocolValue(Protocol.TCP_VALUE);
        final LbEndpoint.Builder lbEndpoint = LbEndpoint
                .newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                                     .setAddress(Address.newBuilder().setSocketAddress(socketAddress)));
        final String clusterName = echo ? ECHO_CLUSTER : NO_ECHO_CLUSTER;
        final ClusterLoadAssignment.Builder loadAssignment = ClusterLoadAssignment
                .newBuilder()
                .setClusterName(clusterName)
                .addEndpoints(LocalityLbEndpoints.newBuilder().addLbEndpoints(lbEndpoint));
        return Cluster.newBuilder()
                      .setName(clusterName)
                      .setType(DiscoveryType.STRICT_DNS)
                      .setLoadAssignment(loadAssignment)
                      .build();
    }

    private static void commit(CentralDogma client, MessageOrBuilder message,
                               String repoName, String clusterName, String fileName) {
        final String json;
        try {
            json = JsonFormatUtil.printer().print(message);
        } catch (InvalidProtocolBufferException e) {
            // Should never reach here.
            throw new Error(e);
        }
        final Change<JsonNode> echoCluster =
                Change.ofJsonUpsert('/' + clusterName + '/' + fileName, json);
        client.forRepo(CONTROL_PLANE_PROJECT, repoName)
              .commit("Add " + clusterName, echoCluster).push().join();
    }

    private static RouteConfiguration createEchoRoute(boolean echo) {
        final VirtualHost.Builder virtualHostBuilder =
                VirtualHost.newBuilder()
                           .setName("all")
                           .addDomains("*")
                           .addRoutes(Route.newBuilder()
                                           .setMatch(RouteMatch.newBuilder().setPrefix("/"))
                                           .setRoute(RouteAction.newBuilder().setCluster(
                                                   echo ? ECHO_CLUSTER : NO_ECHO_CLUSTER)));
        return RouteConfiguration.newBuilder()
                                 .setName(ECHO_ROUTE)
                                 .addVirtualHosts(virtualHostBuilder)
                                 .build();
    }

    private static Listener createEchoListener() {
        final GrpcService.Builder grpcServiceBuilder =
                GrpcService.newBuilder().setEnvoyGrpc(
                        EnvoyGrpc.newBuilder()
                                 .setClusterName(API_CONFIG_SOURCE_CLUSTER_NAME));

        final ConfigSource rdsConfigSource =
                ConfigSource.newBuilder()
                            .setResourceApiVersion(ApiVersion.V3)
                            .setApiConfigSource(
                                    ApiConfigSource.newBuilder()
                                                   .setTransportApiVersion(ApiVersion.V3)
                                                   .setApiType(ApiConfigSource.ApiType.GRPC)
                                                   .addGrpcServices(grpcServiceBuilder))
                            .build();

        final HttpConnectionManager manager =
                HttpConnectionManager.newBuilder()
                                     .setCodecType(CodecType.AUTO)
                                     .setStatPrefix("http")
                                     .setRds(Rds.newBuilder()
                                                .setConfigSource(rdsConfigSource)
                                                .setRouteConfigName(ECHO_ROUTE))
                                     .addHttpFilters(HttpFilter.newBuilder()
                                                               .setName("envoy.filters.http.router")
                                                               .setTypedConfig(Any.pack(
                                                                       Router.newBuilder().build())))
                                     .build();

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
