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

import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;

import java.io.IOException;
import java.net.URI;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Any;
import com.google.protobuf.util.Durations;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.DynamicResources;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.StaticResources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
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
import io.envoyproxy.envoy.config.listener.v3.ApiListener;
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

public final class XdsTestUtil {

    static final String CONFIG_SOURCE_CLUSTER_NAME = "dogma/cluster";

    public static AggregatedHttpResponse createGroup(String groupId, WebClient webClient) {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.POST, "/api/v1/xds/groups?group_id=" + groupId)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .contentType(MediaType.JSON_UTF_8).build();
        return webClient.execute(headers, "{\"name\":\"groups/" + groupId + "\"}")
                        .aggregate().join();
    }

    public static AggregatedHttpResponse deleteGroup(String groupName, WebClient webClient) {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.DELETE, "/api/v1/xds/" + groupName)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .build();
        return webClient.execute(headers).aggregate().join();
    }

    public static LbEndpoint endpoint(String address, int port) {
        final SocketAddress socketAddress = SocketAddress.newBuilder()
                                                         .setAddress(address)
                                                         .setPortValue(port)
                                                         .setProtocol(Protocol.TCP)
                                                         .build();
        return LbEndpoint
                .newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                                     .setAddress(Address.newBuilder()
                                                        .setSocketAddress(socketAddress)
                                                        .build())
                                     .build()).build();
    }

    public static ClusterLoadAssignment loadAssignment(String clusterName, String address, int port) {
        return ClusterLoadAssignment.newBuilder()
                                    .setClusterName(clusterName)
                                    .addEndpoints(
                                            LocalityLbEndpoints.newBuilder()
                                                               .addLbEndpoints(endpoint(address, port)))
                                    .build();
    }

    static Bootstrap bootstrap(URI uri, String clusterName) {
        final Cluster cluster = staticCluster(
                clusterName, loadAssignment(clusterName, uri.getHost(), uri.getPort()));
        final ConfigSource configSource = basicConfigSource(clusterName);
        return bootstrap(configSource, cluster);
    }

    static Bootstrap bootstrap(Cluster cluster) {
        return Bootstrap.newBuilder()
                        .setStaticResources(StaticResources.newBuilder().addClusters(cluster))
                        .build();
    }

    static Bootstrap bootstrap(ConfigSource configSource, Cluster... cluster) {
        return Bootstrap
                .newBuilder()
                .setStaticResources(
                        StaticResources.newBuilder()
                                       .addAllClusters(ImmutableSet.copyOf(cluster)))
                .setDynamicResources(
                        DynamicResources
                                .newBuilder()
                                .setLdsConfig(configSource)
                                .setCdsConfig(configSource))
                .build();
    }

    static ConfigSource rdsConfigSource() {
        final GrpcService.Builder grpcServiceBuilder =
                GrpcService.newBuilder().setEnvoyGrpc(
                        EnvoyGrpc.newBuilder()
                                 .setClusterName(CONFIG_SOURCE_CLUSTER_NAME));

        return ConfigSource.newBuilder()
                           .setResourceApiVersion(ApiVersion.V3)
                           .setApiConfigSource(
                                   ApiConfigSource.newBuilder()
                                                  .setTransportApiVersion(ApiVersion.V3)
                                                  .setApiType(ApiConfigSource.ApiType.GRPC)
                                                  .addGrpcServices(grpcServiceBuilder))
                           .build();
    }

    static ConfigSource basicConfigSource(String clusterName) {
        return ConfigSource
                .newBuilder()
                .setApiConfigSource(apiConfigSource(clusterName, ApiType.GRPC))
                .build();
    }

    static ApiConfigSource apiConfigSource(String clusterName, ApiType apiType) {
        return ApiConfigSource
                .newBuilder()
                .addGrpcServices(
                        GrpcService
                                .newBuilder()
                                .setEnvoyGrpc(EnvoyGrpc.newBuilder()
                                                       .setClusterName(clusterName)))
                .setApiType(apiType)
                .build();
    }

    public static Cluster cluster(String clusterName, int connectTimeoutSeconds) {
        final GrpcService.Builder grpcServiceBuilder =
                GrpcService.newBuilder().setEnvoyGrpc(
                        EnvoyGrpc.newBuilder()
                                 .setClusterName(CONFIG_SOURCE_CLUSTER_NAME));
        final ConfigSource edsSource =
                ConfigSource.newBuilder()
                            .setResourceApiVersion(ApiVersion.V3)
                            .setApiConfigSource(ApiConfigSource.newBuilder()
                                                               .setTransportApiVersion(ApiVersion.V3)
                                                               .setApiType(ApiConfigSource.ApiType.GRPC)
                                                               .addGrpcServices(grpcServiceBuilder).build())
                            .build();
        return cluster(clusterName, edsSource, connectTimeoutSeconds);
    }

    static Cluster cluster(String clusterName, ConfigSource configSource,
                           int connectTimeoutSeconds) {
        return Cluster.newBuilder()
                      .setName(clusterName)
                      .setConnectTimeout(Durations.fromSeconds(connectTimeoutSeconds))
                      .setEdsClusterConfig(
                              Cluster.EdsClusterConfig.newBuilder()
                                                      .setEdsConfig(configSource)
                                                      .setServiceName(clusterName))
                      .setType(Cluster.DiscoveryType.EDS)
                      .build();
    }

    static Cluster cluster(String clusterName, ClusterLoadAssignment loadAssignment,
                           DiscoveryType discoveryType) {
        return Cluster.newBuilder()
                      .setName(clusterName)
                      .setLoadAssignment(loadAssignment)
                      .setType(discoveryType)
                      .build();
    }

    static Cluster staticCluster(String clusterName, ClusterLoadAssignment loadAssignment) {
        final DiscoveryType discoveryType = DiscoveryType.STATIC;
        return cluster(clusterName, loadAssignment, discoveryType);
    }

    public static Listener exampleListener(String listenerName, String routeName, String statPrefix) {
        final HttpConnectionManager manager = httpConnectionManager(routeName, rdsConfigSource());
        return Listener.newBuilder()
                       .setName(listenerName)
                       .setStatPrefix(statPrefix)
                       .setApiListener(ApiListener.newBuilder()
                                                  .setApiListener(Any.pack(manager)))
                       .build();
    }

    static HttpConnectionManager httpConnectionManager(String routeName, ConfigSource rdsConfigSource) {
        return HttpConnectionManager
                .newBuilder()
                .setCodecType(CodecType.AUTO)
                .setStatPrefix("ingress_http")
                .setRds(Rds.newBuilder().setRouteConfigName(routeName).setConfigSource(rdsConfigSource))
                .addHttpFilters(HttpFilter.newBuilder()
                                          .setName("envoy.filters.http.router")
                                          .setTypedConfig(Any.pack(Router.getDefaultInstance())))
                .build();
    }

    static RouteConfiguration routeConfiguration(String routeName, VirtualHost... virtualHosts) {
        return RouteConfiguration.newBuilder()
                                 .setName(routeName)
                                 .addAllVirtualHosts(ImmutableList.copyOf(virtualHosts))
                                 .build();
    }

    public static RouteConfiguration routeConfiguration(String routeName, String clusterName) {
        return routeConfiguration(routeName, virtualHost(routeName, clusterName));
    }

    static VirtualHost virtualHost(String name, String... clusterNames) {
        final VirtualHost.Builder builder =
                VirtualHost.newBuilder().setName(name).addDomains("*");
        for (String clusterName : clusterNames) {
            builder.addRoutes(Route.newBuilder()
                                   .setMatch(RouteMatch.newBuilder().setPrefix("/"))
                                   .setRoute(RouteAction.newBuilder()
                                                        .setCluster(clusterName)));
        }
        return builder.build();
    }

    public static AggregatedHttpResponse createEndpoint(
            String groupName, String endpointId,
            ClusterLoadAssignment endpoint, WebClient webClient) throws IOException {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.POST,
                                       "/api/v1/xds/" + groupName + "/endpoints?endpoint_id=" +
                                       endpointId)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .contentType(MediaType.JSON_UTF_8).build();
        return webClient.execute(headers, JSON_MESSAGE_MARSHALLER.writeValueAsString(endpoint))
                        .aggregate().join();
    }

    public static AggregatedHttpResponse createCluster(
            String groupName, String clusterId, Cluster cluster, WebClient webClient)
            throws IOException {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.POST,
                                       "/api/v1/xds/" + groupName + "/clusters?cluster_id=" + clusterId)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .contentType(MediaType.JSON_UTF_8).build();
        return webClient.execute(headers, JSON_MESSAGE_MARSHALLER.writeValueAsString(cluster))
                        .aggregate().join();
    }

    public static AggregatedHttpResponse updateCluster(
            String groupName, String clusterId, Cluster cluster, WebClient webClient) throws IOException {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.PATCH,
                                       "/api/v1/xds/" + groupName + "/clusters/" + clusterId)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .contentType(MediaType.JSON_UTF_8).build();
        return webClient.execute(headers, JSON_MESSAGE_MARSHALLER.writeValueAsString(cluster))
                        .aggregate().join();
    }

    public static AggregatedHttpResponse createListener(
            String groupName, String listenerId,
            Listener listener, WebClient webClient) throws IOException {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.POST,
                                       "/api/v1/xds/" + groupName + "/listeners?listener_id=" +
                                       listenerId)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .contentType(MediaType.JSON_UTF_8).build();
        return webClient.execute(headers, JSON_MESSAGE_MARSHALLER.writeValueAsString(listener))
                        .aggregate().join();
    }

    public static AggregatedHttpResponse updateListener(
            String groupName, String listenerId, Listener listener, WebClient webClient) throws IOException {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.PATCH,
                                       "/api/v1/xds/" + groupName + "/listeners/" + listenerId)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .contentType(MediaType.JSON_UTF_8).build();
        return webClient.execute(headers, JSON_MESSAGE_MARSHALLER.writeValueAsString(listener))
                        .aggregate().join();
    }

    public static AggregatedHttpResponse createRoute(
            String groupName, String routeId,
            RouteConfiguration route, WebClient webClient) throws IOException {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.POST,
                                       "/api/v1/xds/" + groupName + "/routes?route_id=" +
                                       routeId)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .contentType(MediaType.JSON_UTF_8).build();
        return webClient.execute(headers, JSON_MESSAGE_MARSHALLER.writeValueAsString(route))
                        .aggregate().join();
    }

    public static AggregatedHttpResponse updateRoute(
            String groupName, String routeId,
            RouteConfiguration route, WebClient webClient) throws IOException {
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.PATCH,
                                                              "/api/v1/xds/" + groupName + "/routes/" + routeId)
                                                     .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                                                     .contentType(MediaType.JSON_UTF_8).build();
        return webClient.execute(headers, JSON_MESSAGE_MARSHALLER.writeValueAsString(route))
                        .aggregate().join();
    }

    private XdsTestUtil() {}
}
