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
package com.linecorp.centraldogma.xds.k8s.v1;

import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.K8S_ENDPOINTS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesService.K8S_WATCHERS_DIRECTORY;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesService.WATCHERS_REPLCACE_PATTERN;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesService.WATCHER_NAME_PATTERN;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesService.createKubernetesEndpointGroup;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroup;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.xds.internal.XdsResourceWatchingService;

import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.netty.util.concurrent.DefaultThreadFactory;

final class XdsKubernetesEndpointFetchingService extends XdsResourceWatchingService {

    private static final Logger logger = LoggerFactory.getLogger(XdsKubernetesEndpointFetchingService.class);

    private final CommandExecutor commandExecutor;

    // Only accessed by the executorService.
    private final Map<String, Map<String, KubernetesEndpointsUpdater>> kubernetesEndpointsUpdaters =
            new HashMap<>();

    private final ScheduledExecutorService executorService;
    private volatile boolean stopped;

    XdsKubernetesEndpointFetchingService(Project xdsProject, CommandExecutor commandExecutor,
                                         MeterRegistry meterRegistry) {
        super(xdsProject, "xds.k8s.fetching.service.", meterRegistry);
        this.commandExecutor = commandExecutor;
        executorService = ExecutorServiceMetrics.monitor(
                meterRegistry, Executors.newSingleThreadScheduledExecutor(
                        new DefaultThreadFactory("k8s-plugin-executor", true)), "k8sPluginExecutor");
    }

    void start() {
        init();
    }

    void stop() {
        stopped = true;
        executorService.submit(() -> {
            kubernetesEndpointsUpdaters.values().forEach(map -> {
                map.values().forEach(KubernetesEndpointsUpdater::close);
                map.clear();
            });
            kubernetesEndpointsUpdaters.clear();
        });
    }

    @Override
    protected ScheduledExecutorService executor() {
        return executorService;
    }

    @Override
    protected String pathPattern() {
        return K8S_WATCHERS_DIRECTORY + "**";
    }

    @Override
    protected void handleXdsResource(String path, String contentAsText, String groupName)
            throws InvalidProtocolBufferException {
        final ServiceEndpointWatcher.Builder watcherBuilder = ServiceEndpointWatcher.newBuilder();
        try {
            JSON_MESSAGE_MARSHALLER.mergeValue(contentAsText, watcherBuilder);
        } catch (IOException e) {
            logger.warn("Failed to parse a Watcher at {}{}. content: {}",
                        groupName, path, contentAsText, e);
            return;
        }
        final ServiceEndpointWatcher endpointWatcher = watcherBuilder.build();
        final String watcherName = endpointWatcher.getName();
        logger.info("Creating a service endpoint watcher: {}", watcherName);
        final CompletableFuture<KubernetesEndpointGroup> future =
                createKubernetesEndpointGroup(endpointWatcher, xdsProject().metaRepo(), executorService);
        final Map<String, KubernetesEndpointsUpdater> updaters =
                kubernetesEndpointsUpdaters.computeIfAbsent(groupName, unused -> new HashMap<>());

        final KubernetesEndpointsUpdater oldUpdater = updaters.get(watcherName);
        if (oldUpdater != null) {
            oldUpdater.close();
        }
        final KubernetesEndpointsUpdater updater =
                new KubernetesEndpointsUpdater(commandExecutor, future, executorService,
                                               groupName, watcherName, endpointWatcher.getClusterName());
        updaters.put(watcherName, updater);
        future.handle((kubernetesEndpointGroup, cause) -> {
            if (cause != null) {
                logger.warn("Unexpected exception while creating a KubernetesEndpointGroup in fetching service",
                            cause);
                // Do not remove the updater from updaters because it can remove the updater that is created
                // by the next commit. The updater will be removed only when the file or group is removed.
                updater.close();
                return null;
            }
            kubernetesEndpointGroup.addListener(endpoints -> {
                if (endpoints.isEmpty()) {
                    return;
                }
                executorService.execute(() -> updater.maybeSchedule(kubernetesEndpointGroup));
            }, true);
            return null;
        });
    }

    @Override
    protected void onGroupRemoved(String groupName) {
        final Map<String, KubernetesEndpointsUpdater> watchers = kubernetesEndpointsUpdaters.remove(groupName);
        if (watchers != null) {
            watchers.values().forEach(KubernetesEndpointsUpdater::close);
            watchers.clear();
        }
    }

    @Override
    protected void onFileRemoved(String groupName, String path) {
        final Map<String, KubernetesEndpointsUpdater> updaters = kubernetesEndpointsUpdaters.get(groupName);
        final String watcherName =
                "groups/" + groupName + path.substring(0, path.length() - 5); // Remove .json
        if (updaters != null) {
            // e.g. groups/foo/k8s/watchers/foo-cluster
            final KubernetesEndpointsUpdater updater = updaters.get(watcherName);
            if (updater != null) {
                updater.close();
            }
        }

        // Remove corresponding endpoints.
        final String endpointPath = WATCHERS_REPLCACE_PATTERN.matcher(path).replaceFirst("/endpoints/");
        logger.info("Removing {} from {}. watcherName: {}", endpointPath, groupName, watcherName);
        commandExecutor.execute(
                Command.push(Author.SYSTEM, XDS_CENTRAL_DOGMA_PROJECT, groupName, Revision.HEAD,
                             "Remove " + endpointPath, "",
                             Markup.PLAINTEXT, Change.ofRemoval(endpointPath))).handle((unused, cause) -> {
            if (cause != null) {
                final Throwable peeled = Exceptions.peel(cause);
                if (peeled instanceof ChangeConflictException &&
                    peeled.getMessage().contains("non-existent file")) {
                    // TODO(minwoox): Provide a type to ChangeConflictException to distinguish this case.
                    // Could happen if deleteWatcher is called before the file is created.
                    return null;
                }

                logger.warn("Failed to remove {} from {}", endpointPath, groupName, cause);
            }
            return null;
        });
    }

    @Override
    protected void onDiffHandled() {}

    @Override
    protected boolean isStopped() {
        return stopped;
    }

    private static class KubernetesEndpointsUpdater {

        private final CommandExecutor commandExecutor;
        private final CompletableFuture<KubernetesEndpointGroup> kubernetesEndpointGroupFuture;
        private final ScheduledExecutorService executorService;
        private final String groupName;
        private final String watcherName;
        private final String clusterName;
        @Nullable
        private ScheduledFuture<?> scheduledFuture;

        KubernetesEndpointsUpdater(CommandExecutor commandExecutor,
                                   CompletableFuture<KubernetesEndpointGroup> kubernetesEndpointGroupFuture,
                                   ScheduledExecutorService executorService, String groupName,
                                   String watcherName, String clusterName) {
            this.commandExecutor = commandExecutor;
            this.kubernetesEndpointGroupFuture = kubernetesEndpointGroupFuture;
            this.executorService = executorService;
            this.groupName = groupName;
            this.watcherName = watcherName;
            this.clusterName = clusterName;
        }

        void maybeSchedule(KubernetesEndpointGroup kubernetesEndpointGroup) {
            if (scheduledFuture != null) {
                return;
            }
            // Commit after 1 second so that it pushes with all the fetched endpoints in the duration
            // instead of pushing one by one.
            scheduledFuture = executorService.schedule(() -> {
                scheduledFuture = null;
                // maybeSchedule() is called after the future is completed.
                if (kubernetesEndpointGroup.isClosing()) {
                    return;
                }
                pushK8sEndpoints(kubernetesEndpointGroup);
            }, 1, TimeUnit.SECONDS);
        }

        private void pushK8sEndpoints(KubernetesEndpointGroup kubernetesEndpointGroup) {
            final List<com.linecorp.armeria.client.Endpoint> endpoints =
                    kubernetesEndpointGroup.endpoints();
            if (endpoints.isEmpty()) {
                return;
            }
            logger.debug("Pushing {} endpoints to {}. clusterName: {}",
                         endpoints.size(), groupName, clusterName);

            final LocalityLbEndpoints.Builder localityLbEndpointsBuilder = LocalityLbEndpoints.newBuilder();
            endpoints.forEach(endpoint -> {
                assert endpoint.hasPort();
                final SocketAddress socketAddress = SocketAddress.newBuilder()
                                                                 .setAddress(endpoint.host())
                                                                 .setPortValue(endpoint.port())
                                                                 .build();
                localityLbEndpointsBuilder.addLbEndpoints(
                        LbEndpoint.newBuilder()
                                  .setEndpoint(Endpoint.newBuilder()
                                                       .setAddress(Address.newBuilder()
                                                                          .setSocketAddress(socketAddress)
                                                                          .build()).build())
                                  .build());
            });

            final ClusterLoadAssignment clusterLoadAssignment =
                    ClusterLoadAssignment.newBuilder()
                                         .setClusterName(clusterName)
                                         .addEndpoints(localityLbEndpointsBuilder.build())
                                         .build();
            final String json;
            try {
                json = JSON_MESSAGE_MARSHALLER.writeValueAsString(clusterLoadAssignment);
            } catch (IOException e) {
                // Should never reach here.
                throw new Error(e);
            }
            final Matcher matcher = WATCHER_NAME_PATTERN.matcher(watcherName);
            final boolean matches = matcher.matches();
            assert matches;
            final String watcherId = matcher.group(2);
            final String fileName = K8S_ENDPOINTS_DIRECTORY + watcherId + ".json";
            final Change<JsonNode> change = Change.ofJsonUpsert(fileName, json);
            commandExecutor.execute(
                    Command.push(Author.SYSTEM, XDS_CENTRAL_DOGMA_PROJECT, groupName, Revision.HEAD,
                                 "Add " + clusterName + " with " + endpoints.size() +
                                 " endpoints.", "", Markup.PLAINTEXT, change)).handle((unused, cause) -> {
                if (cause != null) {
                    final Throwable peeled = Exceptions.peel(cause);
                    if (peeled instanceof RedundantChangeException) {
                        // ignore
                        return null;
                    }
                    logger.warn("Failed to push {} to {}", change, groupName, peeled);
                }
                return null;
            });
        }

        void close() {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(true);
            }
            kubernetesEndpointGroupFuture.thenAccept(KubernetesEndpointGroup::closeAsync);
        }
    }
}
