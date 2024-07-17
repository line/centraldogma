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

import static com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer.INTERNAL_PROJECT_DOGMA;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.plugin.AllReplicasPlugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginInitContext;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;

import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import io.envoyproxy.controlplane.cache.SnapshotResources;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.controlplane.server.exception.RequestException;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.netty.util.concurrent.DefaultThreadFactory;

public final class ControlPlanePlugin extends AllReplicasPlugin {

    private static final Logger logger = LoggerFactory.getLogger(ControlPlanePlugin.class);

    public static final String CLUSTER_REPO = "clusters";
    public static final String CLUSTER_FILE = Cluster.getDescriptor().getFullName() + ".json";

    public static final String ENDPOINT_REPO = "endpoints";
    public static final String ENDPOINT_FILE = ClusterLoadAssignment.getDescriptor().getFullName() + ".json";

    public static final String LISTENER_REPO = "listeners";
    public static final String LISTENER_FILE = Listener.getDescriptor().getFullName() + ".json";

    public static final String ROUTE_REPO = "routes";
    public static final String ROUTE_FILE = RouteConfiguration.getDescriptor().getFullName() + ".json";

    public static final String DEFAULT_GROUP = "default_group";

    public static final long BACKOFF_SECONDS = 60; // Should we use backoff?

    private static final ScheduledExecutorService CONTROL_PLANE_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(
                    new DefaultThreadFactory("control-plane-executor", true));

    private volatile boolean stop;

    @Override
    public void init(PluginInitContext pluginInitContext) {
        final InternalProjectInitializer projectInitializer = pluginInitContext.internalProjectInitializer();
        projectInitializer.initializeInternalRepos(
                ImmutableList.of(CLUSTER_REPO, ENDPOINT_REPO, LISTENER_REPO, ROUTE_REPO));

        final ServerBuilder sb = pluginInitContext.serverBuilder();

        // TODO(minwoox): Implement better cache implementation that updates only changed resources.
        final SimpleCache<String> cache = new SimpleCache<>(node -> DEFAULT_GROUP);
        final RepositoryManager repositoryManager = pluginInitContext.projectManager()
                                                                     .get(INTERNAL_PROJECT_DOGMA)
                                                                     .repos();
        watchRepository(repositoryManager.get(CLUSTER_REPO), CLUSTER_FILE, Revision.INIT,
                        (entries, revision) -> updateClusters(entries, revision, cache));
        watchRepository(repositoryManager.get(ENDPOINT_REPO), ENDPOINT_FILE, Revision.INIT,
                        (entries, revision) -> updateEndpoints(entries, revision, cache));
        watchRepository(repositoryManager.get(LISTENER_REPO), LISTENER_FILE, Revision.INIT,
                        (entries, revision) -> updateListeners(entries, revision, cache));
        watchRepository(repositoryManager.get(ROUTE_REPO), ROUTE_FILE, Revision.INIT,
                        (entries, revision) -> routes(entries, revision, cache));
        final V3DiscoveryServer server = new V3DiscoveryServer(new LoggingDiscoveryServerCallbacks(), cache);
        // xDS, ADS
        final GrpcService grpcService = GrpcService.builder()
                                                   .addService(server.getClusterDiscoveryServiceImpl())
                                                   .addService(server.getEndpointDiscoveryServiceImpl())
                                                   .addService(server.getListenerDiscoveryServiceImpl())
                                                   .addService(server.getRouteDiscoveryServiceImpl())
                                                   .addService(server.getSecretDiscoveryServiceImpl())
                                                   .addService(server.getAggregatedDiscoveryServiceImpl())
                                                   .useBlockingTaskExecutor(true)
                                                   .build();
        sb.route().build(grpcService);
    }

    private void watchRepository(Repository repository, String fileName, Revision revision,
                                 ThrowingBiConsumer<Collection<Entry<?>>, Revision> updatingSnapshotFunction) {
        final CompletableFuture<Revision> future = repository.watch(revision, "/**");
        future.handleAsync((BiFunction<Revision, Throwable, Void>) (watchedRevision, cause) -> {
            if (stop) {
                return null;
            }
            if (cause != null) {
                logger.warn("Unexpected exception is raised while watching {}. Try watching after {} seconds..",
                            repository, BACKOFF_SECONDS, cause);
                CONTROL_PLANE_EXECUTOR.schedule(
                        () -> watchRepository(repository, fileName, revision, updatingSnapshotFunction),
                        BACKOFF_SECONDS, TimeUnit.SECONDS);
                return null;
            }
            final CompletableFuture<Map<String, Entry<?>>> entriesFuture =
                    repository.find(watchedRevision, "/**/" + fileName);
            entriesFuture.handleAsync(
                    (BiFunction<Map<String, Entry<?>>, Throwable, Void>) (entries, findCause) -> {
                        if (stop) {
                            return null;
                        }
                        if (findCause != null) {
                            logger.warn(
                                    "Unexpected exception is raised while finding {}" +
                                    " with revision {} from {}. Try watching after {} seconds..",
                                    fileName, watchedRevision, repository, BACKOFF_SECONDS, findCause);
                            CONTROL_PLANE_EXECUTOR.schedule(
                                    () -> watchRepository(repository, fileName,
                                                          watchedRevision, updatingSnapshotFunction),
                                    BACKOFF_SECONDS, TimeUnit.SECONDS);
                            return null;
                        }
                        try {
                            updatingSnapshotFunction.accept(entries.values(), watchedRevision);
                            CONTROL_PLANE_EXECUTOR.execute(() -> watchRepository(
                                    repository, fileName, watchedRevision, updatingSnapshotFunction));
                        } catch (Throwable t) {
                            logger.warn("Unexpected exception is raised while building from {} using {}" +
                                        ". Try watching after {} seconds..",
                                        repository.name(), entries.values(), BACKOFF_SECONDS, t);
                            CONTROL_PLANE_EXECUTOR.schedule(
                                    () -> watchRepository(
                                            repository, fileName, watchedRevision, updatingSnapshotFunction),
                                    BACKOFF_SECONDS, TimeUnit.SECONDS);
                        }
                        return null;
                    },
                    CONTROL_PLANE_EXECUTOR);
            return null;
        }, CONTROL_PLANE_EXECUTOR);
    }

    private static boolean updateClusters(Collection<Entry<?>> entries, Revision revision,
                                          SimpleCache<String> cache) throws InvalidProtocolBufferException {
        final Builder<Cluster> clustersBuilder = ImmutableList.builder();
        for (Entry<?> entry : entries) {
            final Cluster.Builder clusterBuilder = Cluster.newBuilder();
            JsonFormatUtil.parser().merge(entry.contentAsText(), clusterBuilder);
            clustersBuilder.add(clusterBuilder.build());
        }

        setNewSnapshot(cache, ResourceType.CLUSTER,
                       CentralDogmaSnapshotResources.create(clustersBuilder.build(), revision));
        return true;
    }

    private static boolean updateEndpoints(Collection<Entry<?>> entries, Revision revision,
                                           SimpleCache<String> cache) throws InvalidProtocolBufferException {
        final Builder<ClusterLoadAssignment> endpointsBuilder = ImmutableList.builder();
        for (Entry<?> entry : entries) {
            final ClusterLoadAssignment.Builder endpointBuilder = ClusterLoadAssignment.newBuilder();
            JsonFormatUtil.parser().merge(entry.contentAsText(), endpointBuilder);
            endpointsBuilder.add(endpointBuilder.build());
        }

        setNewSnapshot(cache, ResourceType.ENDPOINT,
                       CentralDogmaSnapshotResources.create(endpointsBuilder.build(), revision));
        return true;
    }

    private static boolean updateListeners(Collection<Entry<?>> entries, Revision revision,
                                           SimpleCache<String> cache) throws InvalidProtocolBufferException {
        final Builder<Listener> listenersBuilder = ImmutableList.builder();
        for (Entry<?> entry : entries) {
            final Listener.Builder listenerBuilder = Listener.newBuilder();
            JsonFormatUtil.parser().merge(entry.contentAsText(), listenerBuilder);
            listenersBuilder.add(listenerBuilder.build());
        }

        setNewSnapshot(cache, ResourceType.LISTENER,
                       CentralDogmaSnapshotResources.create(listenersBuilder.build(), revision));
        return true;
    }

    private static boolean routes(Collection<Entry<?>> entries, Revision revision, SimpleCache<String> cache)
            throws InvalidProtocolBufferException {
        final Builder<RouteConfiguration> routesBuilder = ImmutableList.builder();
        for (Entry<?> entry : entries) {
            final RouteConfiguration.Builder routeBuilder = RouteConfiguration.newBuilder();
            JsonFormatUtil.parser().merge(entry.contentAsText(), routeBuilder);
            routesBuilder.add(routeBuilder.build());
        }

        setNewSnapshot(cache, ResourceType.ROUTE,
                       CentralDogmaSnapshotResources.create(routesBuilder.build(), revision));
        return true;
    }

    @SuppressWarnings("unchecked")
    private static void setNewSnapshot(SimpleCache<String> cache, ResourceType resourceType,
                                       SnapshotResources<?> resources) {
        SnapshotResources<Cluster> clusters;
        SnapshotResources<ClusterLoadAssignment> endpoints;
        SnapshotResources<Listener> listeners;
        SnapshotResources<RouteConfiguration> routes;
        SnapshotResources<Secret> secrets;
        final Snapshot previousSnapshot = cache.getSnapshot(DEFAULT_GROUP);
        if (previousSnapshot == null) {
            final SnapshotResources<?> emptyResources =
                    SnapshotResources.create(ImmutableList.of(), "empty_resources");
            clusters = (SnapshotResources<Cluster>) emptyResources;
            endpoints = (SnapshotResources<ClusterLoadAssignment>) emptyResources;
            listeners = (SnapshotResources<Listener>) emptyResources;
            routes = (SnapshotResources<RouteConfiguration>) emptyResources;
            secrets = (SnapshotResources<Secret>) emptyResources;
        } else {
            clusters = previousSnapshot.clusters();
            endpoints = previousSnapshot.endpoints();
            listeners = previousSnapshot.listeners();
            routes = previousSnapshot.routes();
            secrets = previousSnapshot.secrets();
        }
        switch (resourceType) {
            case CLUSTER:
                clusters = (SnapshotResources<Cluster>) resources;
                break;
            case ENDPOINT:
                endpoints = (SnapshotResources<ClusterLoadAssignment>) resources;
                break;
            case LISTENER:
                listeners = (SnapshotResources<Listener>) resources;
                break;
            case ROUTE:
                routes = (SnapshotResources<RouteConfiguration>) resources;
                break;
            case SECRET:
                secrets = (SnapshotResources<Secret>) resources;
                break;
            default:
                // Should never reach here.
                throw new Error();
        }

        cache.setSnapshot(DEFAULT_GROUP,
                          new CentralDogmaSnapshot(clusters, endpoints, listeners, routes, secrets));
    }

    @Override
    public CompletionStage<Void> start(PluginContext context) {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> stop(PluginContext context) {
        stop = true;
        return UnmodifiableFuture.completedFuture(null);
    }

    @FunctionalInterface
    interface ThrowingBiConsumer<A, B> {
        void accept(A a, B b) throws Exception;
    }

    private static final class LoggingDiscoveryServerCallbacks implements DiscoveryServerCallbacks {
        @Override
        public void onV3StreamRequest(long streamId, DiscoveryRequest request) throws RequestException {
            logger.debug("Received v3 stream request. streamId: {}, version: {}, resource_names: {}, " +
                         "response_nonce: {}, type_url: {}",
                         streamId, request.getVersionInfo(), request.getResourceNamesList(),
                         request.getResponseNonce(), request.getTypeUrl());
        }

        @Override
        public void onV3StreamDeltaRequest(long streamId, DeltaDiscoveryRequest request)
                throws RequestException {}

        @Override
        public void onV3StreamResponse(long streamId, DiscoveryRequest request,
                                       DiscoveryResponse response) {
            logger.debug("Sent v3 stream response. streamId: {}, onV3StreamResponse: {}", streamId, response);
        }
    }
}
