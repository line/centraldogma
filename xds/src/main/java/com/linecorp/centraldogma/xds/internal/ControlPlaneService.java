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

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;
import com.linecorp.centraldogma.xds.internal.k8s.XdsKubernetesService;

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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.netty.util.concurrent.DefaultThreadFactory;

public final class ControlPlaneService extends XdsProjectWatchingService {

    private static final Logger logger = LoggerFactory.getLogger(ControlPlaneService.class);

    public static final String K8S_ENDPOINTS_DIRECTORY = "/k8s/endpoints/";

    static final String CLUSTERS_DIRECTORY = "/clusters/";
    static final String ENDPOINTS_DIRECTORY = "/endpoints/";
    static final String LISTENERS_DIRECTORY = "/listeners/";
    static final String ROUTES_DIRECTORY = "/routes/";

    private static final String PATH_PATTERN =
            "/clusters/**,/endpoints/**,/k8s/endpoints/**,/listeners/**,/routes/**";

    private static final String DEFAULT_GROUP = "default_group";

    // TODO(minwoox): Implement better cache implementation that updates only changed resources
    //                instead of this snapshot based implementation.
    private final SimpleCache<String> cache = new SimpleCache<>(node -> DEFAULT_GROUP);

    private final ScheduledExecutorService controlPlaneExecutor;
    // Accessed only from controlPlaneExecutor.
    private final CentralDogmaXdsResources centralDogmaXdsResources = new CentralDogmaXdsResources();
    private volatile boolean stop;

    ControlPlaneService(RepositoryManager repositoryManager, MeterRegistry meterRegistry) {
        super(repositoryManager);
        controlPlaneExecutor = ExecutorServiceMetrics.monitor(
                meterRegistry,
                Executors.newSingleThreadScheduledExecutor(
                        new DefaultThreadFactory("control-plane-executor", true)),
                "controlPlaneExecutor");
    }

    Future<Void> start(ServerBuilder serverBuilder, CommandExecutor commandExecutor) {
        return controlPlaneExecutor.submit(() -> {
            start();
            final V3DiscoveryServer server = new V3DiscoveryServer(new LoggingDiscoveryServerCallbacks(),
                                                                   cache);
            final GrpcService grpcService = GrpcService.builder()
                                                       .addService(server.getClusterDiscoveryServiceImpl())
                                                       .addService(server.getEndpointDiscoveryServiceImpl())
                                                       .addService(server.getListenerDiscoveryServiceImpl())
                                                       .addService(server.getRouteDiscoveryServiceImpl())
                                                       .addService(server.getAggregatedDiscoveryServiceImpl())
                                                       .addService(new XdsKubernetesService(commandExecutor))
                                                       .enableHttpJsonTranscoding(true)
                                                       .useBlockingTaskExecutor(true)
                                                       .build();
            serverBuilder.route().build(grpcService);
            return null;
        });
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
    protected void handleXdsResources(String path, String contentAsText, String repoName)
            throws InvalidProtocolBufferException {
        if (path.startsWith(CLUSTERS_DIRECTORY)) {
            final Cluster.Builder builder = Cluster.newBuilder();
            JsonFormatUtil.parser().merge(contentAsText, builder);
            centralDogmaXdsResources.setCluster(repoName, builder.build());
        } else if (path.startsWith(ENDPOINTS_DIRECTORY) || path.startsWith(K8S_ENDPOINTS_DIRECTORY)) {
            final ClusterLoadAssignment.Builder builder = ClusterLoadAssignment.newBuilder();
            JsonFormatUtil.parser().merge(contentAsText, builder);
            centralDogmaXdsResources.setEndpoint(repoName, builder.build());
        } else if (path.startsWith(LISTENERS_DIRECTORY)) {
            final Listener.Builder builder = Listener.newBuilder();
            JsonFormatUtil.parser().merge(contentAsText, builder);
            centralDogmaXdsResources.setListener(repoName, builder.build());
        } else if (path.startsWith(ROUTES_DIRECTORY)) {
            final RouteConfiguration.Builder builder = RouteConfiguration.newBuilder();
            JsonFormatUtil.parser().merge(contentAsText, builder);
            centralDogmaXdsResources.setRoute(repoName, builder.build());
        } else {
            // ignore
        }
    }

    @Override
    protected void onRepositoryRemoved(String repoName) {
        centralDogmaXdsResources.removeProject(repoName);
        cache.setSnapshot(DEFAULT_GROUP, centralDogmaXdsResources.snapshot());
    }

    @Override
    protected void onFileRemoved(String repoName, String path) {
        if (path.startsWith(CLUSTERS_DIRECTORY)) {
            centralDogmaXdsResources.removeCluster(repoName, path);
        } else if (path.startsWith(ENDPOINTS_DIRECTORY) ||
                   path.startsWith(K8S_ENDPOINTS_DIRECTORY)) {
            centralDogmaXdsResources.removeEndpoint(repoName, path);
        } else if (path.startsWith(LISTENERS_DIRECTORY)) {
            centralDogmaXdsResources.removeListener(repoName, path);
        } else if (path.startsWith(ROUTES_DIRECTORY)) {
            centralDogmaXdsResources.removeRoute(repoName, path);
        }
    }

    @Override
    protected void onDiffHandled() {
        cache.setSnapshot(DEFAULT_GROUP, centralDogmaXdsResources.snapshot());
    }

    @Override
    protected boolean isStopped() {
        return stop;
    }

    void stop() {
        stop = true;
        final boolean interrupted = terminate(controlPlaneExecutor);
        if (interrupted) {
            Thread.currentThread().interrupt();
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
