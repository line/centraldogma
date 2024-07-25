/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.client.armeria.xds;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import com.google.protobuf.Any;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.encoding.DecodingClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsEndpointGroup;
import com.linecorp.centraldogma.client.AbstractCentralDogmaBuilder;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.ArmeriaClientConfigurator;
import com.linecorp.centraldogma.internal.client.ReplicationLagTolerantCentralDogma;
import com.linecorp.centraldogma.internal.client.armeria.ArmeriaCentralDogma;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.DynamicResources;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.StaticResources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.GrpcService.EnvoyGrpc;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;

/**
 * Builds a {@link CentralDogma} client based on an <a href="https://line.github.io/armeria/">Armeria</a>
 * HTTP client.
 * This client differs from {@link ArmeriaCentralDogma} in that making requests is done in two phases.
 * <ol>
 *     <li>
 *         An xDS request is made to xDS servers to fetch a {@link Listener} resource which represents
 *         how to connect to {@link CentralDogma} servers.
 *     </li>
 *     <li>
 *         Actual {@link CentralDogma} client requests are made based on the watched Listener resource.
 *     </li>
 * </ol>
 * Because connection to the actual {@link CentralDogma} is determined by the watched {@link Listener},
 * {@link #hosts()} and {@link #isUseTls()} apply to connecting to the bootstrap only.
 * However, because Armeria's xDS implementation isn't complete, the following parameters are applied
 * to both xDS and Central Dogma requests.
 * <ul>
 *     <li>{@link #accessToken(String)}</li>
 *     <li>{@link #clientConfigurator(ArmeriaClientConfigurator)}</li>
 *     <li>{@link #clientFactory(ClientFactory)}</li>
 * </ul>
 *
 * <p>Note that this module is considered experimental and subject to behavioral change.
 */
@UnstableApi
public final class XdsCentralDogmaBuilder extends AbstractCentralDogmaBuilder<XdsCentralDogmaBuilder> {

    private static final String BOOTSTRAP_CLUSTER_NAME = "centraldogma-bootstrap-cluster";
    @VisibleForTesting
    static final String DEFAULT_LISTENER_NAME = "centraldogma-listener";

    private ScheduledExecutorService blockingTaskExecutor = CommonPools.blockingTaskExecutor();
    private ClientFactory clientFactory = ClientFactory.ofDefault();
    private ArmeriaClientConfigurator clientConfigurator = cb -> {};
    private String listenerName = DEFAULT_LISTENER_NAME;
    private Locality locality = Locality.getDefaultInstance();
    // an empty string means no local cluster
    private String serviceCluster = "";

    // TODO: @jrhee17 remove this once xDS TLS is fully supported
    private Function<Bootstrap, XdsBootstrap> xdsBootstrapFactory = XdsBootstrap::of;

    /**
     * Sets the name of the {@link Listener} that should be requested to the xDS bootstrap servers.
     * The default is {@value #DEFAULT_LISTENER_NAME}.
     */
    public XdsCentralDogmaBuilder listenerName(String listenerName) {
        requireNonNull(listenerName, "listenerName");
        this.listenerName = listenerName;
        return this;
    }

    /**
     * Sets the locality of where the {@link CentralDogma} client will be running. This may be used in applying
     * <a href="https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/load_balancing/zone_aware">zone aware routing</a>
     * and is analogous to
     * <a href="https://www.envoyproxy.io/docs/envoy/latest/operations/cli#cmdoption-service-zone">service-zone</a>.
     * This value will be set to {@link Node#getLocality()} in the {@link Bootstrap}.
     */
    @UnstableApi
    public XdsCentralDogmaBuilder serviceZone(String serviceZone) {
        requireNonNull(serviceZone, "serviceZone");
        locality = Locality.newBuilder().setZone(serviceZone).build();
        return this;
    }

    /**
     * Sets the name of the local service cluster which this client will be located in.
     * This may be used in applying
     * <a href="https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/load_balancing/zone_aware">zone aware routing</a>
     * and is analogous to
     * <a href="https://www.envoyproxy.io/docs/envoy/latest/operations/cli#cmdoption-service-cluster">service-cluster</a>.
     * This value will be set to {@link Node#getCluster()} in the {@link Bootstrap}.
     */
    @UnstableApi
    public XdsCentralDogmaBuilder serviceCluster(String serviceCluster) {
        requireNonNull(serviceCluster, "serviceCluster");
        this.serviceCluster = serviceCluster;
        return this;
    }

    /**
     * Sets the {@link ScheduledExecutorService} dedicated to the execution of blocking tasks or invocations.
     * If not set, {@linkplain CommonPools#blockingTaskExecutor() the common pool} is used.
     * The {@link ScheduledExecutorService} which will be used for scheduling the tasks related with
     * automatic retries and invoking the callbacks for watched changes.
     */
    public XdsCentralDogmaBuilder blockingTaskExecutor(ScheduledExecutorService blockingTaskExecutor) {
        requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        this.blockingTaskExecutor = blockingTaskExecutor;
        return this;
    }

    /**
     * Sets the {@link ArmeriaClientConfigurator} that will configure an underlying
     * <a href="https://line.github.io/armeria/">Armeria</a> client which performs the actual socket I/O.
     *
     * <p>Note that this doesn't affect the client making requests to the bootstrap servers.
     */
    public XdsCentralDogmaBuilder clientConfigurator(ArmeriaClientConfigurator clientConfigurator) {
        this.clientConfigurator = requireNonNull(clientConfigurator, "clientConfigurator");
        return this;
    }

    /**
     * Sets the {@link ClientFactory} that will create an underlying
     * <a href="https://line.github.io/armeria/">Armeria</a> client which performs the actual socket I/O.
     *
     * <p>Note that this doesn't affect the client making requests to the bootstrap servers.
     */
    public XdsCentralDogmaBuilder clientFactory(ClientFactory clientFactory) {
        this.clientFactory = requireNonNull(clientFactory, "clientFactory");
        return this;
    }

    @VisibleForTesting
    XdsCentralDogmaBuilder xdsBoostrapFactory(Function<Bootstrap, XdsBootstrap> xdsBootstrapFactory) {
        this.xdsBootstrapFactory = requireNonNull(xdsBootstrapFactory, "xdsBootstrapFactory");
        return this;
    }

    /**
     * Returns a newly-created {@link CentralDogma} instance.
     */
    public CentralDogma build() {
        final XdsBootstrap xdsBootstrap = xdsBootstrap();
        final String listenerName = this.listenerName;
        final EndpointGroup endpointGroup = XdsEndpointGroup.of(xdsBootstrap.listenerRoot(listenerName));
        final String scheme = "none+" + (isUseTls() ? "https" : "http");
        final ClientBuilder builder =
                newClientBuilder(scheme, endpointGroup, cb -> cb.decorator(DecodingClient.newDecorator()), "/");
        final int maxRetriesOnReplicationLag = maxNumRetriesOnReplicationLag();

        // TODO(ikhoon): Apply ExecutorServiceMetrics for the 'blockingTaskExecutor' once
        //               https://github.com/line/centraldogma/pull/542 is merged.
        final ScheduledExecutorService blockingTaskExecutor = this.blockingTaskExecutor;

        final CentralDogma dogma = new ArmeriaCentralDogma(blockingTaskExecutor,
                                                           builder.build(WebClient.class),
                                                           accessToken(),
                                                           () -> {
                                                               endpointGroup.close();
                                                               xdsBootstrap.close();
                                                           });
        if (maxRetriesOnReplicationLag <= 0) {
            return dogma;
        } else {
            return new ReplicationLagTolerantCentralDogma(
                    blockingTaskExecutor, dogma, maxRetriesOnReplicationLag,
                    retryIntervalOnReplicationLagMillis(),
                    () -> {
                        // FIXME(trustin): Note that this will always return `null` due to a known limitation
                        //                 in Armeria: https://github.com/line/armeria/issues/760
                        final ClientRequestContext ctx = ClientRequestContext.currentOrNull();
                        return ctx != null ? ctx.remoteAddress() : null;
                    });
        }
    }

    private ClientBuilder newClientBuilder(String scheme, EndpointGroup endpointGroup,
                                           Consumer<ClientBuilder> customizer, String path) {
        final ClientBuilder builder = Clients.builder(scheme, endpointGroup, path);
        customizer.accept(builder);
        clientConfigurator.configure(builder);
        builder.factory(clientFactory);
        return builder;
    }

    private boolean isUnresolved() {
        final Set<InetSocketAddress> hosts = hosts();
        checkState(!hosts.isEmpty(), "No hosts were added.");
        final Map<Boolean, List<InetSocketAddress>> addrByUnresolved =
                hosts.stream().collect(Collectors.partitioningBy(InetSocketAddress::isUnresolved));
        // Until multiple clusters are supported, restrict users to either use STATIC or DNS (but not both)
        checkState(addrByUnresolved.get(true).isEmpty() ||
                   addrByUnresolved.get(false).isEmpty(),
                   "Cannot mix resolved and unresolved hosts (%s)", addrByUnresolved);
        final InetSocketAddress firstHost = Iterables.get(hosts(), 0);
        return firstHost.isUnresolved();
    }

    private XdsBootstrap xdsBootstrap() {
        final GrpcService grpcService = GrpcService
                .newBuilder()
                .setEnvoyGrpc(EnvoyGrpc.newBuilder()
                                       .setClusterName(BOOTSTRAP_CLUSTER_NAME))
                .addInitialMetadata(HeaderValue.newBuilder()
                                               .setKey(HttpHeaderNames.AUTHORIZATION.toString())
                                               .setValue("Bearer " + accessToken()))
                .build();
        final ApiConfigSource apiConfigSource = ApiConfigSource
                .newBuilder()
                .addGrpcServices(grpcService)
                .setApiType(ApiType.AGGREGATED_GRPC)
                .build();
        final DynamicResources dynamicResources =
                DynamicResources.newBuilder().setAdsConfig(apiConfigSource).build();
        final Bootstrap bootstrap =
                Bootstrap.newBuilder()
                         .setDynamicResources(dynamicResources)
                         .setNode(Node.newBuilder()
                                      .setCluster(serviceCluster)
                                      .setLocality(locality))
                         .setStaticResources(StaticResources.newBuilder().addClusters(bootstrapCluster()))
                         .build();
        return xdsBootstrapFactory.apply(bootstrap);
    }

    private Cluster bootstrapCluster() {
        final boolean isUnresolved = isUnresolved();

        final Cluster.Builder clusterBuilder = Cluster.newBuilder();
        if (isUnresolved) {
            clusterBuilder.setType(DiscoveryType.STRICT_DNS);
        } else {
            clusterBuilder.setType(DiscoveryType.STATIC);
        }

        final LocalityLbEndpoints.Builder localityLbEndpointsBuilder = LocalityLbEndpoints.newBuilder();
        for (InetSocketAddress addr : hosts()) {
            final LbEndpoint lbEndpoint = fromAddress(addr);
            localityLbEndpointsBuilder.addLbEndpoints(lbEndpoint);
        }
        final ClusterLoadAssignment clusterLoadAssignment =
                ClusterLoadAssignment.newBuilder().addEndpoints(localityLbEndpointsBuilder.build()).build();

        if (isUseTls()) {
            clusterBuilder.setTransportSocket(
                    TransportSocket.newBuilder()
                                   .setName("envoy.transport_sockets.tls")
                                   .setTypedConfig(Any.pack(UpstreamTlsContext.getDefaultInstance())));
        }

        clusterBuilder.setLoadAssignment(clusterLoadAssignment)
                      .setName(BOOTSTRAP_CLUSTER_NAME);
        return clusterBuilder.build();
    }

    private static LbEndpoint fromAddress(InetSocketAddress addr) {
        final String hostString = addr.getHostString();
        final int port = addr.getPort();
        final Address address = Address.newBuilder()
                                       .setSocketAddress(SocketAddress.newBuilder()
                                                                      .setAddress(hostString)
                                                                      .setPortValue(port)).build();
        return LbEndpoint.newBuilder()
                         .setEndpoint(Endpoint.newBuilder()
                                              .setAddress(address))
                         .build();
    }
}
