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

import static com.linecorp.centraldogma.xds.internal.XdsServiceUtil.JSON_MESSAGE_MARSHALLER;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.plugin.AllReplicasPlugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginInitContext;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.DiffResultType;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;
import com.linecorp.centraldogma.xds.cluster.v1.XdsClusterService;
import com.linecorp.centraldogma.xds.endpoint.v1.XdsEndpointService;
import com.linecorp.centraldogma.xds.group.v1.XdsGroupService;
import com.linecorp.centraldogma.xds.listener.v1.XdsListenerService;
import com.linecorp.centraldogma.xds.route.v1.XdsRouteService;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.controlplane.server.exception.RequestException;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.netty.util.concurrent.DefaultThreadFactory;

public final class ControlPlanePlugin extends AllReplicasPlugin {

    private static final Logger logger = LoggerFactory.getLogger(ControlPlanePlugin.class);

    public static final String XDS_CENTRAL_DOGMA_PROJECT = "@xds";

    public static final String CLUSTERS_DIRECTORY = "/clusters/";
    public static final String ENDPOINTS_DIRECTORY = "/endpoints/";
    public static final String LISTENERS_DIRECTORY = "/listeners/";
    public static final String ROUTES_DIRECTORY = "/routes/";

    public static final String DEFAULT_GROUP = "default_group";

    public static final long BACKOFF_SECONDS = 10;

    private static final ScheduledExecutorService CONTROL_PLANE_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(
                    new DefaultThreadFactory("control-plane-executor", true));

    private volatile boolean stop;

    // TODO(minwoox): Implement better cache implementation that updates only changed resources
    //                instead of this snapshot based implementation.
    private final SimpleCache<String> cache = new SimpleCache<>(node -> DEFAULT_GROUP);

    // Accessed only from CONTROL_PLANE_EXECUTOR.
    private final Set<String> watchingXdsProjects = new HashSet<>();
    private final CentralDogmaXdsResources centralDogmaXdsResources = new CentralDogmaXdsResources();

    @Override
    public void init(PluginInitContext pluginInitContext) {
        final InternalProjectInitializer projectInitializer = pluginInitContext.internalProjectInitializer();
        projectInitializer.initialize(XDS_CENTRAL_DOGMA_PROJECT);

        try {
            CONTROL_PLANE_EXECUTOR.submit(() -> init0(pluginInitContext))
                                  .get(BACKOFF_SECONDS, TimeUnit.SECONDS);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to init control plane plugin in " + BACKOFF_SECONDS +
                                       " seconds.", t);
        }
    }

    private void init0(PluginInitContext pluginInitContext) {
        final ServerBuilder sb = pluginInitContext.serverBuilder();
        final ProjectManager projectManager = pluginInitContext.projectManager();
        final RepositoryManager repositoryManager = projectManager.get(XDS_CENTRAL_DOGMA_PROJECT).repos();
        for (Repository repository : repositoryManager.list().values()) {
            final String repoName = repository.name();
            if (Project.REPO_META.equals(repoName) || Project.REPO_DOGMA.equals(repoName)) {
                continue;
            }
            // A Central Dogma repository is an xDS project.
            watchingXdsProjects.add(repoName);

            final Revision normalizedRevision = repository.normalizeNow(Revision.HEAD);
            logger.info("Creating xDS resources from {} project at revision: {}", repoName, normalizedRevision);
            final Map<String, Entry<?>> entries = repository.find(normalizedRevision, "/**").join();
            for (Entry<?> entry : entries.values()) {
                final String path = entry.path();
                final String contentAsText = entry.contentAsText();
                try {
                    setXdsResources(path, contentAsText, repoName);
                } catch (Throwable t) {
                    throw new RuntimeException("Unexpected exception while building an xDS resource from " +
                                               repoName + path, t);
                }
            }

            watchXdsProject(repository, normalizedRevision);
        }

        // Watch dogma repository to add newly created xDS projects.
        watchDogmaRepository(repositoryManager, Revision.INIT);
        final V3DiscoveryServer server = new V3DiscoveryServer(new LoggingDiscoveryServerCallbacks(), cache);
        final GrpcService grpcService = GrpcService.builder()
                                                   .addService(server.getClusterDiscoveryServiceImpl())
                                                   .addService(server.getEndpointDiscoveryServiceImpl())
                                                   .addService(server.getListenerDiscoveryServiceImpl())
                                                   .addService(server.getRouteDiscoveryServiceImpl())
                                                   .addService(server.getAggregatedDiscoveryServiceImpl())
                                                   .useBlockingTaskExecutor(true)
                                                   .build();
        sb.route().build(grpcService);
        final CommandExecutor commandExecutor = pluginInitContext.commandExecutor();
        final GrpcService xdsApplicationService =
                GrpcService.builder()
                           .addService(new XdsGroupService(projectManager, commandExecutor))
                           .addService(new XdsListenerService(projectManager, commandExecutor))
                           .addService(new XdsRouteService(projectManager, commandExecutor))
                           .addService(new XdsClusterService(projectManager, commandExecutor))
                           .addService(new XdsEndpointService(projectManager, commandExecutor))
                           .jsonMarshallerFactory(
                                   serviceDescriptor -> GrpcJsonMarshaller
                                           .builder()
                                           //TODO(minwoox): Automate the registration of the extension messages.
                                           .jsonMarshallerCustomizer(builder -> {
                                               builder.register(HttpConnectionManager.getDefaultInstance())
                                                      .register(Router.getDefaultInstance());
                                           })
                                           .build(serviceDescriptor))
                           .enableHttpJsonTranscoding(true).build();
        sb.service(xdsApplicationService, pluginInitContext.authService());
    }

    private void setXdsResources(String path, String contentAsText, String repoName)
            throws IOException {
        if (path.startsWith(CLUSTERS_DIRECTORY)) {
            final Cluster.Builder builder = Cluster.newBuilder();
            JSON_MESSAGE_MARSHALLER.mergeValue(contentAsText, builder);
            centralDogmaXdsResources.setCluster(repoName, builder.build());
        } else if (path.startsWith(ENDPOINTS_DIRECTORY)) {
            final ClusterLoadAssignment.Builder builder =
                    ClusterLoadAssignment.newBuilder();
            JSON_MESSAGE_MARSHALLER.mergeValue(contentAsText, builder);
            centralDogmaXdsResources.setEndpoint(repoName, builder.build());
        } else if (path.startsWith(LISTENERS_DIRECTORY)) {
            final Listener.Builder builder = Listener.newBuilder();
            JSON_MESSAGE_MARSHALLER.mergeValue(contentAsText, builder);
            centralDogmaXdsResources.setListener(repoName, builder.build());
        } else if (path.startsWith(ROUTES_DIRECTORY)) {
            final RouteConfiguration.Builder builder = RouteConfiguration.newBuilder();
            JSON_MESSAGE_MARSHALLER.mergeValue(contentAsText, builder);
            centralDogmaXdsResources.setRoute(repoName, builder.build());
        } else {
            // ignore
        }
    }

    private void watchDogmaRepository(RepositoryManager repositoryManager, Revision lastKnownRevision) {
        final Repository dogmaRepository = repositoryManager.get(Project.REPO_DOGMA);
        // TODO(minwoox): Use different file because metadata.json contains other information than repo's names.
        dogmaRepository.watch(lastKnownRevision, Query.ofJson(MetadataService.METADATA_JSON))
                       .handleAsync((entry, cause) -> {
                           if (cause != null) {
                               logger.warn("Failed to watch {} in xDS. Try watching after {} seconds.",
                                           MetadataService.METADATA_JSON, BACKOFF_SECONDS, cause);
                               CONTROL_PLANE_EXECUTOR.schedule(
                                       () -> watchDogmaRepository(repositoryManager, lastKnownRevision),
                                       BACKOFF_SECONDS, TimeUnit.SECONDS);
                               return null;
                           }
                           final JsonNode content = entry.content();
                           final JsonNode repos = content.get("repos");
                           if (repos == null) {
                               logger.warn("Failed to find repos in {} in xDS. Try watching after {} seconds.",
                                           MetadataService.METADATA_JSON, BACKOFF_SECONDS);
                               CONTROL_PLANE_EXECUTOR.schedule(
                                       () -> watchDogmaRepository(repositoryManager, lastKnownRevision),
                                       BACKOFF_SECONDS, TimeUnit.SECONDS);
                               return null;
                           }
                           repos.fieldNames().forEachRemaining(repoName -> {
                               if (Project.REPO_META.equals(repoName)) {
                                   return;
                               }
                               final boolean added = watchingXdsProjects.add(repoName);
                               if (!added) {
                                   // Already watching.
                                   return;
                               }
                               final Repository repository = repositoryManager.get(repoName);
                               if (repository == null) {
                                   // Ignore if the repository is removed. This can happen when multiple
                                   // updates occurred after the actual repository is removed from the file
                                   // system but before the repository is removed from the metadata file.
                                   watchingXdsProjects.remove(repoName);
                                   return;
                               }
                               watchXdsProject(repository, Revision.INIT);
                           });
                           // Watch dogma repository again to catch up newly created xDS projects.
                           watchDogmaRepository(repositoryManager, entry.revision());
                           return null;
                       }, CONTROL_PLANE_EXECUTOR);
    }

    private void watchXdsProject(Repository repository, Revision lastKnownRevision) {
        final CompletableFuture<Revision> watchFuture = repository.watch(lastKnownRevision, "/**");
        watchFuture.handleAsync((BiFunction<Revision, Throwable, Void>) (newRevision, cause) -> {
            if (stop) {
                return null;
            }
            if (cause != null) {
                if (cause instanceof RepositoryNotFoundException) {
                    // Repository is removed.
                    watchingXdsProjects.remove(repository.name());
                    centralDogmaXdsResources.removeGroup(repository.name());
                    cache.setSnapshot(DEFAULT_GROUP, centralDogmaXdsResources.snapshot());
                    return null;
                }
                logger.warn("Unexpected exception while watching {} at {}. Try watching after {} seconds.",
                            repository.name(), lastKnownRevision, BACKOFF_SECONDS, cause);
                CONTROL_PLANE_EXECUTOR.schedule(() -> watchXdsProject(repository, lastKnownRevision),
                                                BACKOFF_SECONDS, TimeUnit.SECONDS);
                return null;
            }
            final CompletableFuture<Map<String, Change<?>>> diffFuture =
                    repository.diff(lastKnownRevision, newRevision, "/**", DiffResultType.PATCH_TO_UPSERT);
            handleDiff(repository, newRevision, diffFuture, lastKnownRevision);
            return null;
        }, CONTROL_PLANE_EXECUTOR);
    }

    private void handleDiff(Repository repository, Revision newRevision,
                            CompletableFuture<Map<String, Change<?>>> diffFuture, Revision lastKnownRevision) {
        diffFuture.handleAsync((BiFunction<Map<String, Change<?>>, Throwable, Void>) (changes, cause) -> {
            if (stop) {
                return null;
            }
            final String repoName = repository.name();
            if (cause != null) {
                logger.warn("Unexpected exception while diffing {} from {} to {}. Building from the first.",
                            repoName, lastKnownRevision, newRevision, cause);
                centralDogmaXdsResources.removeGroup(repoName);
                // Do not call cache.setSnapshot(). Let watchXdsProject() create a new snapshot.
                watchXdsProject(repository, Revision.INIT);
                return null;
            }

            logger.info("Creating xDS resources from {} project using {} to {}. The number of changes: {}",
                        repoName, lastKnownRevision, newRevision, changes.size());
            for (Change<?> change : changes.values()) {
                final String path = change.path();
                switch (change.type()) {
                    case UPSERT_JSON:
                        try {
                            setXdsResources(path, change.contentAsText(), repoName);
                        } catch (Throwable t) {
                            logger.warn("Unexpected exception while building an xDS resource from {}.",
                                        repoName + path, t);
                        }
                        break;
                    case REMOVE:
                        if (path.startsWith(CLUSTERS_DIRECTORY)) {
                            centralDogmaXdsResources.removeCluster(repoName, path);
                        } else if (path.startsWith(ENDPOINTS_DIRECTORY)) {
                            centralDogmaXdsResources.removeEndpoint(repoName, path);
                        } else if (path.startsWith(LISTENERS_DIRECTORY)) {
                            centralDogmaXdsResources.removeListener(repoName, path);
                        } else if (path.startsWith(ROUTES_DIRECTORY)) {
                            centralDogmaXdsResources.removeRoute(repoName, path);
                        }
                        break;
                    default:
                        // Ignore other types of changes.
                        // No APPLY_JSON_PATCH because the diff option.
                        // No RENAME because the resource name in the content always have to be
                        // changed if the file is renamed.
                        if (lastKnownRevision.major() != 1) {
                            logger.warn("Unexpected change type: {} from {} to {} at {}.",
                                        change.type(), lastKnownRevision, newRevision, path);
                        }
                        break;
                }
            }
            cache.setSnapshot(DEFAULT_GROUP, centralDogmaXdsResources.snapshot());
            watchXdsProject(repository, newRevision);
            return null;
        }, CONTROL_PLANE_EXECUTOR);
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

    @Override
    public Class<?> configType() {
        return ControlPlanePluginConfig.class;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("configType", configType())
                          .add("target", target())
                          .toString();
    }

    private static final class LoggingDiscoveryServerCallbacks implements DiscoveryServerCallbacks {
        @Override
        public void onV3StreamRequest(long streamId, DiscoveryRequest request) throws RequestException {
            logger.debug("Received v3 stream request. streamId: {}, version: {}, resource_names: {}, " +
                         "response_nonce: {}, type_url: {}", streamId, request.getVersionInfo(),
                         request.getResourceNamesList(), request.getResponseNonce(), request.getTypeUrl());
        }

        @Override
        public void onV3StreamDeltaRequest(long streamId, DeltaDiscoveryRequest request)
                throws RequestException {}

        @Override
        public void onV3StreamResponse(long streamId, DiscoveryRequest request, DiscoveryResponse response) {
            logger.debug("Sent v3 stream response. streamId: {}, version: {}, " +
                         "response_nonce: {}, type_url: {}", streamId, response.getVersionInfo(),
                         response.getNonce(), response.getTypeUrl());
        }
    }
}
