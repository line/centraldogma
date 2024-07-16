/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.centraldogma.client.armeria.xds;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;

class BasicTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
        }
    };

    @RegisterExtension
    static CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar")
                  .join()
                  .commit("Initial file", Change.ofJsonUpsert("/foo.json", "{ \"a\": \"bar\" }"))
                  .push()
                  .join();
        }
    };

    @BeforeEach
    void beforeEach() {
        final Listener listener = listener(XdsCentralDogmaBuilder.LISTENER_NAME, "my-route");
        final RouteConfiguration route = routeConfiguration("my-route", "my-cluster");
        final Cluster cluster = cluster("my-cluster");
        final ClusterLoadAssignment loadAssignment =
                loadAssignment("my-cluster", "127.0.0.1", dogma.serverAddress().getPort());
        cache.setSnapshot(
                GROUP,
                Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(loadAssignment),
                                ImmutableList.of(listener), ImmutableList.of(route), ImmutableList.of(), "1"));
    }

    @Test
    void basicCase() throws Exception {
        final CentralDogma client = new XdsCentralDogmaBuilder()
                .host("127.0.0.1", server.httpPort())
                .build();
        final Entry<JsonNode> entry = client.forRepo("foo", "bar")
                                            .file(Query.ofJsonPath("/foo.json"))
                                            .get()
                                            .get();
        assertThatJson(entry.content()).node("a").isStringEqualTo("bar");
    }

    private static Cluster cluster(String clusterName) {
        final ConfigSource edsSource =
                ConfigSource.newBuilder()
                            .setInitialFetchTimeout(Duration.newBuilder().setSeconds(0))
                            .setAds(AggregatedConfigSource.getDefaultInstance())
                            .setResourceApiVersion(ApiVersion.V3)
                            .build();
        return createCluster(clusterName, edsSource);
    }

    public static Cluster createCluster(String clusterName, ConfigSource configSource) {
        return Cluster.newBuilder()
                      .setName(clusterName)
                      .setEdsClusterConfig(
                              Cluster.EdsClusterConfig.newBuilder()
                                                      .setEdsConfig(configSource)
                                                      .setServiceName(clusterName))
                      .setType(Cluster.DiscoveryType.EDS)
                      .build();
    }

    private static LbEndpoint endpoint(String address, int port) {
        final SocketAddress socketAddress = SocketAddress.newBuilder()
                                                         .setAddress(address)
                                                         .setPortValue(port)
                                                         .build();
        return LbEndpoint
                .newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                                     .setAddress(Address.newBuilder()
                                                        .setSocketAddress(socketAddress)
                                                        .build())
                                     .build()).build();
    }

    private static ClusterLoadAssignment loadAssignment(String clusterName, String address, int port) {
        return ClusterLoadAssignment.newBuilder()
                                    .setClusterName(clusterName)
                                    .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                                     .addLbEndpoints(endpoint(address, port)))
                                    .build();
    }

    private static Listener listener(String listenerName, String routeName) {
        final HttpConnectionManager manager =
                httpConnectionManager(Rds.newBuilder().setRouteConfigName(routeName).build());
        return listener(listenerName, manager);
    }

    private static Listener listener(String listenerName, HttpConnectionManager manager) {
        return Listener.newBuilder()
                       .setName(listenerName)
                       .setApiListener(ApiListener.newBuilder()
                                                  .setApiListener(Any.pack(manager)))
                       .build();
    }

    private static HttpConnectionManager httpConnectionManager(Rds rds) {
        return HttpConnectionManager
                .newBuilder()
                .setRds(rds)
                .addHttpFilters(HttpFilter.newBuilder()
                                          .setName("envoy.filters.http.router")
                                          .setTypedConfig(Any.pack(Router.getDefaultInstance())))
                .build();
    }

    private static RouteConfiguration routeConfiguration(String routeName, VirtualHost... virtualHosts) {
        return RouteConfiguration.newBuilder()
                                 .setName(routeName)
                                 .addAllVirtualHosts(ImmutableList.copyOf(virtualHosts))
                                 .build();
    }

    private static RouteConfiguration routeConfiguration(String routeName, String clusterName) {
        return routeConfiguration(routeName, virtualHost(routeName, clusterName));
    }

    private static VirtualHost virtualHost(String name, String... clusterNames) {
        final VirtualHost.Builder builder =
                VirtualHost.newBuilder().setName(name).addDomains("*");
        for (String clusterName: clusterNames) {
            builder.addRoutes(Route.newBuilder()
                                   .setMatch(RouteMatch.newBuilder().setPrefix("/"))
                                   .setRoute(RouteAction.newBuilder()
                                                        .setCluster(clusterName)));
        }
        return builder.build();
    }
}
