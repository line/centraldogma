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
package com.linecorp.centraldogma.xds.internal;

import static com.linecorp.centraldogma.server.internal.ExecutorServiceUtil.terminate;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.grpc.DefaultJsonMarshaller;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.auth.Authorizer;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.internal.api.auth.ApplicationCertificateAuthorizer;
import com.linecorp.centraldogma.server.internal.api.auth.ApplicationTokenAuthorizer;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.metadata.UserWithAppIdentity;
import com.linecorp.centraldogma.server.plugin.PluginInitContext;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.xds.cluster.v1.XdsClusterService;
import com.linecorp.centraldogma.xds.endpoint.v1.XdsEndpointService;
import com.linecorp.centraldogma.xds.group.v1.XdsGroupService;
import com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesService;
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
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.PrototypeMarshaller;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.netty.util.concurrent.DefaultThreadFactory;

public final class ControlPlaneService extends XdsResourceWatchingService {

    private static final Logger logger = LoggerFactory.getLogger(ControlPlaneService.class);

    public static final String K8S_ENDPOINTS_DIRECTORY = "/k8s/endpoints/";

    public static final String CLUSTERS_DIRECTORY = "/clusters/";
    public static final String ENDPOINTS_DIRECTORY = "/endpoints/";
    public static final String LISTENERS_DIRECTORY = "/listeners/";
    public static final String ROUTES_DIRECTORY = "/routes/";

    private static final String PATH_PATTERN =
            "/clusters/**,/endpoints/**,/k8s/endpoints/**,/listeners/**,/routes/**";

    private static final String DEFAULT_GROUP = "default_group";

    // TODO(minwoox): Implement better cache implementation that updates only changed resources
    //                instead of this snapshot based implementation.
    private final SimpleCache<String> cache = new SimpleCache<>(node -> cacheKey());

    // Entries are keyed per app identity (not per connection), so the size is bounded by the number of distinct
    // app identities that have ever connected to the discovery API, and entries are never removed. This is fine
    // for a stable, finite set of app identities. If app identities churn, will implement evicting an entry
    // when its last stream closes.
    private final Map<String, ScopedClient> scopedClients = new ConcurrentHashMap<>();

    private final ScheduledExecutorService controlPlaneExecutor;
    private final ControlPlaneMetrics metrics;
    // Mutated only from the controlPlaneExecutor.
    private final CentralDogmaXdsResources centralDogmaXdsResources = new CentralDogmaXdsResources();
    @Nullable
    private volatile XdsEndpointService xdsEndpointService;
    private volatile boolean stop;

    ControlPlaneService(Project xdsProject, MeterRegistry meterRegistry) {
        super(xdsProject, "xds.control.plane.service.", meterRegistry);
        controlPlaneExecutor = ExecutorServiceMetrics.monitor(
                meterRegistry,
                Executors.newSingleThreadScheduledExecutor(
                        new DefaultThreadFactory("control-plane-executor", true)),
                "controlPlaneExecutor");
        metrics = new ControlPlaneMetrics(meterRegistry);
    }

    void start(PluginInitContext pluginInitContext) {
        init();
        // No scoped client has connected yet, so this only sets the DEFAULT_GROUP snapshot.
        updateAllSnapshots(null);
        final CommandExecutor commandExecutor = pluginInitContext.commandExecutor();
        final V3DiscoveryServer server = new V3DiscoveryServer(new LoggingDiscoveryServerCallbacks(), cache);
        final GrpcServiceBuilder grpcServiceBuilder =
                GrpcService.builder()
                           .addService(server.getClusterDiscoveryServiceImpl())
                           .addService(server.getEndpointDiscoveryServiceImpl())
                           .addService(server.getListenerDiscoveryServiceImpl())
                           .addService(server.getRouteDiscoveryServiceImpl())
                           .addService(server.getAggregatedDiscoveryServiceImpl());
        final GrpcService grpcService = grpcServiceBuilder.build();
        // Clients without an app id are served the full snapshot under DEFAULT_GROUP,
        // preserving backward compatibility.
        final MetadataService mds = new MetadataService(pluginInitContext.projectManager(), commandExecutor,
                                                        pluginInitContext.internalProjectInitializer());
        pluginInitContext.serverBuilder().service(grpcService, optionalAppIdentityAuth(mds));
        final XdsResourceManager xdsResourceManager = new XdsResourceManager(xdsProject(), commandExecutor);
        final XdsEndpointService xdsEndpointService =
                new XdsEndpointService(xdsResourceManager, controlPlaneExecutor);
        this.xdsEndpointService = xdsEndpointService;
        final GrpcService xdsApplicationService =
                GrpcService.builder()
                           .addService(new XdsGroupService(xdsProject(), commandExecutor, mds))
                           .addService(new XdsListenerService(xdsResourceManager))
                           .addService(new XdsRouteService(xdsResourceManager))
                           .addService(new XdsClusterService(xdsResourceManager))
                           .addService(xdsEndpointService)
                           .addService(new XdsKubernetesService(xdsResourceManager))
                           .exceptionHandler(new ControlPlaneExceptionHandlerFunction())
                           .jsonMarshallerFactory(
                                   serviceDescriptor -> {
                                       // Use JSON_MESSAGE_MARSHALLER not to parse Envoy extensions twice.
                                       final MessageMarshaller.Builder builder =
                                               JSON_MESSAGE_MARSHALLER.toBuilder();
                                       for (MethodDescriptor<?, ?> method : ImmutableList.copyOf(
                                               serviceDescriptor.getMethods())) {
                                           final Message reqPrototype =
                                                   marshallerPrototype(method.getRequestMarshaller());
                                           final Message resPrototype =
                                                   marshallerPrototype(method.getResponseMarshaller());
                                           if (reqPrototype != null) {
                                               builder.register(reqPrototype);
                                           }
                                           if (resPrototype != null) {
                                               builder.register(resPrototype);
                                           }
                                       }
                                       return new DefaultJsonMarshaller(builder.build());
                                   })
                           .enableHttpJsonTranscoding(true).build();
        pluginInitContext.serverBuilder().service(xdsApplicationService, pluginInitContext.authService());

        // Endpoints (EDS) are not access-controlled: any authenticated user can read the endpoints of every
        // group regardless of its READ access.
        pluginInitContext.serverBuilder()
                         .annotatedService()
                         .pathPrefix("/api/v1")
                         .decorator(pluginInitContext.authService())
                         .build(new XdsEndpointReadService(xdsProject()));

        registerWebEnabledFlag(pluginInitContext.serverBuilder());
    }

    /**
     * Registers the {@code /api/v1/xds/web} endpoint that signals the xDS web UI is available. The UI itself
     * is bundled into and served by the main web application under {@code /app/xds}; this flag is registered
     * only when the control plane plugin is loaded, so the web app reveals the xDS link accordingly.
     */
    private static void registerWebEnabledFlag(ServerBuilder sb) {
        sb.service("/api/v1/xds/web", (ctx, req) -> HttpResponse.ofJson(ImmutableMap.of("enabled", true)));
    }

    @Nullable
    private static Message marshallerPrototype(Marshaller<?> marshaller) {
        if (marshaller instanceof MethodDescriptor.PrototypeMarshaller) {
            final Object prototype = ((PrototypeMarshaller<?>) marshaller).getMessagePrototype();
            if (prototype instanceof Message) {
                return (Message) prototype;
            }
        }
        return null;
    }

    @Override
    protected ScheduledExecutorService executor() {
        return controlPlaneExecutor;
    }

    @Override
    protected String pathPattern() {
        return PATH_PATTERN;
    }

    @Override
    protected void handleXdsResource(String path, String contentAsText, String groupName)
            throws IOException {
        if (path.startsWith(CLUSTERS_DIRECTORY)) {
            final Cluster.Builder builder = Cluster.newBuilder();
            JSON_MESSAGE_MARSHALLER.mergeValue(contentAsText, builder);
            centralDogmaXdsResources.setCluster(groupName, builder.build());
        } else if (path.startsWith(ENDPOINTS_DIRECTORY) || path.startsWith(K8S_ENDPOINTS_DIRECTORY)) {
            final ClusterLoadAssignment.Builder builder = ClusterLoadAssignment.newBuilder();
            JSON_MESSAGE_MARSHALLER.mergeValue(contentAsText, builder);
            centralDogmaXdsResources.setEndpoint(groupName, builder.build());
        } else if (path.startsWith(LISTENERS_DIRECTORY)) {
            final Listener.Builder builder = Listener.newBuilder();
            JSON_MESSAGE_MARSHALLER.mergeValue(contentAsText, builder);
            centralDogmaXdsResources.setListener(groupName, builder.build());
        } else if (path.startsWith(ROUTES_DIRECTORY)) {
            final RouteConfiguration.Builder builder = RouteConfiguration.newBuilder();
            JSON_MESSAGE_MARSHALLER.mergeValue(contentAsText, builder);
            centralDogmaXdsResources.setRoute(groupName, builder.build());
        } else {
            // ignore
        }
    }

    @Override
    protected void onGroupRemoved(String groupName) {
        centralDogmaXdsResources.removeGroup(groupName);
        updateAllSnapshots(groupName);
    }

    @Override
    protected void onFileRemoved(String groupName, String path) {
        if (path.startsWith(CLUSTERS_DIRECTORY)) {
            centralDogmaXdsResources.removeCluster(groupName, path);
        } else if (path.startsWith(ENDPOINTS_DIRECTORY) ||
                   path.startsWith(K8S_ENDPOINTS_DIRECTORY)) {
            centralDogmaXdsResources.removeEndpoint(groupName, path);
        } else if (path.startsWith(LISTENERS_DIRECTORY)) {
            centralDogmaXdsResources.removeListener(groupName, path);
        } else if (path.startsWith(ROUTES_DIRECTORY)) {
            centralDogmaXdsResources.removeRoute(groupName, path);
        }
    }

    @Override
    protected void onDiffHandled(String groupName) {
        updateAllSnapshots(groupName);
    }

    @Override
    protected void onMetadataChanged() {
        if (scopedClients.isEmpty()) {
            return;
        }
        // The cached Project.metadata() can lag behind this listener, so read the latest metadata.
        fetchXdsMetadata().handleAsync((metadata, cause) -> {
            if (cause != null) {
                logger.warn("Failed to read the xDS project metadata; scoped snapshots were not refreshed.",
                            cause);
                return null;
            }
            // Permissions may have changed, so recompute each client's readable groups and rebuild its
            // snapshot.
            scopedClients.forEach((key, scopedClient) -> refreshScopedClient(key, scopedClient, metadata));
            return null;
        }, controlPlaneExecutor);
    }

    private CompletableFuture<ProjectMetadata> fetchXdsMetadata() {
        return xdsProject().repos().get(Project.REPO_DOGMA)
                           .get(Revision.HEAD, Query.ofJson(MetadataService.METADATA_JSON))
                           .thenApply(entry -> {
                               try {
                                   return Jackson.treeToValue(entry.content(), ProjectMetadata.class);
                               } catch (JsonProcessingException e) {
                                   throw new CompletionException(e);
                               }
                           });
    }

    /**
     * Returns a decorator that authenticates a client with either its mTLS client certificate or an application
     * access token, but does NOT reject clients that present neither.
     */
    private static Function<? super HttpService, ? extends HttpService> optionalAppIdentityAuth(
            MetadataService mds) {
        final Authorizer<HttpRequest> authorizer =
                new ApplicationCertificateAuthorizer(mds::findCertificateById)
                        .orElse(new ApplicationTokenAuthorizer(mds::findTokenBySecret));
        return delegate -> new SimpleDecoratingHttpService(delegate) {
            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                // The authorizer sets the authenticated user on the context when the certificate or token is
                // valid. The boolean result is ignored on purpose.
                return HttpResponse.of(authorizer.authorize(ctx, req).handle((authorized, cause) -> {
                    try {
                        return unwrap().serve(ctx, req);
                    } catch (Exception e) {
                        return Exceptions.throwUnsafely(e);
                    }
                }));
            }
        };
    }

    /**
     * Rebuilds the snapshots after a change. {@code changedGroup} is the group whose resources changed, or
     * {@code null} to refresh every scoped client (e.g. on start). Only the clients affected by the change are
     * rebuilt: a scoped client is refreshed when it can read {@code changedGroup}, or unconditionally when the
     * change touched endpoints (which are shared unfiltered across all clients) or {@code changedGroup} is
     * {@code null}.
     */
    private void updateAllSnapshots(@Nullable String changedGroup) {
        // Read before snapshot() resets the update flags.
        final boolean endpointsChanged = centralDogmaXdsResources.isEndpointUpdated();
        final CentralDogmaSnapshot snapshot = centralDogmaXdsResources.snapshot();
        cache.setSnapshot(DEFAULT_GROUP, snapshot);
        metrics.onSnapshotUpdate(snapshot);
        final boolean refreshAll = endpointsChanged || changedGroup == null;
        scopedClients.forEach((key, scopedClient) -> {
            final Set<String> groups = scopedClient.readableGroups;
            if (groups != null && (refreshAll || groups.contains(changedGroup))) {
                cache.setSnapshot(key, centralDogmaXdsResources.snapshot(groups));
            }
        });
    }

    private String cacheKey() {
        final User user = AuthUtil.currentUserOrNull();
        if (!(user instanceof UserWithAppIdentity) || user.isSystemAdmin()) {
            return DEFAULT_GROUP;
        }
        final UserWithAppIdentity appIdentity = (UserWithAppIdentity) user;
        // login is the app id for UserWithAppIdentity.
        final String key = "app/" + appIdentity.login();
        if (!scopedClients.containsKey(key)) {
            // First time we see this app identity; compute its readable groups and build its scoped snapshot
            // synchronously. The snapshot is set in the cache BEFORE the client is registered in scopedClients,
            // so a concurrent request that resolves to this key never observes it without a snapshot. A racing
            // first request may redundantly recompute it, which is harmless.
            final ScopedClient scopedClient = new ScopedClient(appIdentity);
            final ProjectMetadata metadata = xdsProject().metadata();
            assert metadata != null;
            refreshScopedClient(key, scopedClient, metadata);
            scopedClients.putIfAbsent(key, scopedClient);
        }
        return key;
    }

    private void refreshScopedClient(String key, ScopedClient scopedClient, ProjectMetadata metadata) {
        final Set<String> groups = groupsWithReadAccess(metadata, scopedClient.appIdentity);
        scopedClient.readableGroups = groups;
        cache.setSnapshot(key, centralDogmaXdsResources.snapshot(groups));
    }

    private static Set<String> groupsWithReadAccess(ProjectMetadata metadata, User user) {
        final ImmutableSet.Builder<String> groups = ImmutableSet.builder();
        for (String groupName : metadata.repos().keySet()) {
            // Any repository role implies at least READ access.
            if (MetadataService.findRepositoryRole(metadata, groupName, user) != null) {
                groups.add(groupName);
            }
        }
        return groups.build();
    }

    @Override
    protected boolean isStopped() {
        return stop;
    }

    void stop() {
        stop = true;
        metrics.onStopped();
        final XdsEndpointService xdsEndpointService = this.xdsEndpointService;
        if (xdsEndpointService != null) {
            if (xdsEndpointService.batchUpdateTaskSize() > 0) {
                logger.info("Waiting for {} xDS endpoint batch update tasks to finish up to 5 seconds...",
                            xdsEndpointService.batchUpdateTaskSize());
                for (int i = 0; i < 5; i++) {
                    try {
                        if (xdsEndpointService.batchUpdateTaskSize() == 0) {
                            break;
                        }
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        final boolean interrupted = terminate(controlPlaneExecutor);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * An app identity authenticated on the discovery API together with the set of groups it currently has READ
     * access to.
     */
    private static final class ScopedClient {

        private final UserWithAppIdentity appIdentity;

        // null until the readable groups are first computed when the client connects.
        @Nullable
        private volatile Set<String> readableGroups;

        ScopedClient(UserWithAppIdentity appIdentity) {
            this.appIdentity = appIdentity;
        }
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
