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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.K8S_ENDPOINTS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesService.AGGREGATORS_REPLCACE_PATTERN;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesService.K8S_ENDPOINT_AGGREGATORS_DIRECTORY;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesService.K8S_ENDPOINT_AGGREGATORS_NAME_PATTERN;
import static com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesService.createKubernetesEndpointGroup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.InvalidProtocolBufferException;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroup;
import org.jspecify.annotations.Nullable;
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
    public static final CompletableFuture<?>[] EMPTY_FUTURES = new CompletableFuture[0];

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
        return K8S_ENDPOINT_AGGREGATORS_DIRECTORY + "**";
    }

    @Override
    protected void handleXdsResource(String path, String contentAsText, String groupName)
            throws InvalidProtocolBufferException {
        final KubernetesEndpointAggregator.Builder aggregatorBuilder =
                KubernetesEndpointAggregator.newBuilder();
        try {
            JSON_MESSAGE_MARSHALLER.mergeValue(contentAsText, aggregatorBuilder);
        } catch (IOException e) {
            logger.warn("Failed to parse a KubernetesEndpointAggregator at {}{}. content: {}",
                        groupName, path, contentAsText, e);
            return;
        }

        final KubernetesEndpointAggregator aggregator = aggregatorBuilder.build();
        final String aggregatorName = aggregator.getName();

        final Map<String, KubernetesEndpointsUpdater> updaters =
                kubernetesEndpointsUpdaters.computeIfAbsent(groupName, unused -> new HashMap<>());

        logger.info("Creating service endpoint watchers of {}.", aggregatorName);
        final List<CompletableFuture<KubernetesEndpointGroup>> futures = new ArrayList<>();
        for (KubernetesLocalityLbEndpoints kubernetesLocalityLbEndpoints
                : aggregator.getLocalityLbEndpointsList()) {
            final ServiceEndpointWatcher watcher = kubernetesLocalityLbEndpoints.getWatcher();
            final CompletableFuture<KubernetesEndpointGroup> future =
                    createKubernetesEndpointGroup(watcher, xdsProject().metaRepo(), groupName, path, true);
            futures.add(future);
        }

        final KubernetesEndpointsUpdater oldUpdater = updaters.get(aggregatorName);
        if (oldUpdater != null) {
            oldUpdater.close();
        }
        final KubernetesEndpointsUpdater updater =
                new KubernetesEndpointsUpdater(commandExecutor, futures, executorService,
                                               groupName, aggregator);
        updaters.put(aggregatorName, updater);
        CompletableFuture.allOf(futures.toArray(EMPTY_FUTURES)).exceptionally(cause -> {
            logger.warn("Unexpected exception while creating a KubernetesEndpointGroup in fetching service",
                        cause);
            // Do not remove the updater from updaters because it can remove the updater that is created
            // by the next commit. The updater will be removed only when the file or group is removed.
            updater.close();
            return null;
        });
    }

    @Override
    protected void onGroupRemoved(String groupName) {
        final Map<String, KubernetesEndpointsUpdater> updaters = kubernetesEndpointsUpdaters.remove(groupName);
        if (updaters != null) {
            updaters.values().forEach(KubernetesEndpointsUpdater::close);
            updaters.clear();
        }
    }

    @Override
    protected void onFileRemoved(String groupName, String path) {
        final Map<String, KubernetesEndpointsUpdater> updaters = kubernetesEndpointsUpdaters.get(groupName);
        // e.g. groups/foo/k8s/endpointAggregators/foo-cluster
        final String aggregatorName =
                "groups/" + groupName + path.substring(0, path.length() - 5); // Remove .json
        if (updaters != null) {
            final KubernetesEndpointsUpdater updater = updaters.get(aggregatorName);
            if (updater != null) {
                updater.close();
            }
        }

        // Remove corresponding endpoints.
        final String endpointPath = AGGREGATORS_REPLCACE_PATTERN.matcher(path).replaceFirst("/endpoints/");
        logger.info("Removing {} from {}. aggregatorName: {}", endpointPath, groupName, aggregatorName);
        commandExecutor.execute(
                Command.push(Author.SYSTEM, XDS_CENTRAL_DOGMA_PROJECT, groupName, Revision.HEAD,
                             "Remove " + endpointPath, "",
                             Markup.PLAINTEXT, Change.ofRemoval(endpointPath))).handle((unused, cause) -> {
            if (cause != null) {
                final Throwable peeled = Exceptions.peel(cause);
                if (peeled instanceof ChangeConflictException &&
                    peeled.getMessage().contains("non-existent file")) {
                    // TODO(minwoox): Provide a type to ChangeConflictException to distinguish this case.
                    // Could happen if deleteKubernetesEndpointAggregator is called before the file is created.
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
        private final List<CompletableFuture<KubernetesEndpointGroup>> kubernetesEndpointGroupFutures;
        private final ScheduledExecutorService executorService;
        private final String groupName;
        private final KubernetesEndpointAggregator aggregator;
        @Nullable
        private ScheduledFuture<?> scheduledFuture;
        private boolean closing;

        KubernetesEndpointsUpdater(
                CommandExecutor commandExecutor,
                List<CompletableFuture<KubernetesEndpointGroup>> kubernetesEndpointGroupFutures,
                ScheduledExecutorService executorService, String groupName,
                KubernetesEndpointAggregator aggregator) {
            this.commandExecutor = commandExecutor;
            this.kubernetesEndpointGroupFutures = kubernetesEndpointGroupFutures;
            this.executorService = executorService;
            this.groupName = groupName;
            this.aggregator = aggregator;

            CompletableFutures.successfulAsList(kubernetesEndpointGroupFutures, t -> null)
                              .thenAccept(endpointGroups -> {
                                  final List<CompletableFuture<List<com.linecorp.armeria.client.Endpoint>>>
                                          whenReadys = endpointGroups.stream()
                                                                     .filter(Objects::nonNull)
                                                                     .map(KubernetesEndpointGroup::whenReady)
                                                                     .collect(toImmutableList());
                                  CompletableFutures.successfulAsList(whenReadys, t -> null)
                                                    .thenAccept(unused -> addListenerToEndpointGroups());
                              });
        }

        private void addListenerToEndpointGroups() {
            kubernetesEndpointGroupFutures.forEach(future -> future.thenAccept(kubernetesEndpointGroup -> {
                if (kubernetesEndpointGroup.whenReady().isCompletedExceptionally()) {
                    logger.warn("Failed to initialize k8s endpoint group: {}, aggregator: {}",
                                kubernetesEndpointGroup, aggregator);
                    return;
                }
                final AtomicBoolean initialized = new AtomicBoolean();
                kubernetesEndpointGroup.addListener(endpoints -> {
                    if (endpoints.isEmpty()) {
                        return;
                    }
                    if (initialized.compareAndSet(false, true)) {
                        executorService.execute(this::pushK8sEndpoints);
                        return;
                    }

                    executorService.execute(this::maybeSchedule);
                }, true);
            }));
        }

        void maybeSchedule() {
            if (closing || scheduledFuture != null) {
                return;
            }
            // Commit after 3 seconds so that it pushes with all the fetched endpoints in the duration
            // instead of pushing one by one.
            scheduledFuture = executorService.schedule(() -> {
                scheduledFuture = null;
                pushK8sEndpoints();
            }, 3, TimeUnit.SECONDS);
        }

        private void pushK8sEndpoints() {
            if (closing) {
                return;
            }
            logger.debug("Pushing k8s endpoints: {}, group: {}", aggregator.getClusterName(), groupName);
            final ClusterLoadAssignment.Builder clusterLoadAssignmentBuilder =
                    ClusterLoadAssignment.newBuilder().setClusterName(aggregator.getClusterName());

            for (int i = 0; i < kubernetesEndpointGroupFutures.size(); i++) {
                final CompletableFuture<KubernetesEndpointGroup> future =
                        kubernetesEndpointGroupFutures.get(i);
                final KubernetesLocalityLbEndpoints localityLbEndpoints = aggregator.getLocalityLbEndpoints(i);
                addLocalityLbEndpoints(clusterLoadAssignmentBuilder, future, localityLbEndpoints);
            }
            if (clusterLoadAssignmentBuilder.getEndpointsCount() == 0) {
                logger.warn("No endpoints found for {}. group: {}", aggregator.getClusterName(), groupName);
                return;
            }

            final String json;
            try {
                json = JSON_MESSAGE_MARSHALLER.writeValueAsString(clusterLoadAssignmentBuilder.build());
            } catch (IOException e) {
                // Should never reach here.
                throw new Error(e);
            }
            final Matcher matcher = K8S_ENDPOINT_AGGREGATORS_NAME_PATTERN.matcher(aggregator.getName());
            final boolean matches = matcher.matches();
            assert matches;
            final String aggregatorId = matcher.group(2);
            final String fileName = K8S_ENDPOINTS_DIRECTORY + aggregatorId + ".json";
            final Change<JsonNode> change = Change.ofJsonUpsert(fileName, json);
            commandExecutor.execute(
                    Command.push(Author.SYSTEM, XDS_CENTRAL_DOGMA_PROJECT, groupName, Revision.HEAD,
                                 "Add " + aggregator.getClusterName() + '.', "",
                                 Markup.PLAINTEXT, change)).handle((unused, cause) -> {
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

        private static void addLocalityLbEndpoints(
                ClusterLoadAssignment.Builder clusterLoadAssignmentBuilder,
                CompletableFuture<KubernetesEndpointGroup> future,
                KubernetesLocalityLbEndpoints kubernetesLocalityLbEndpoints) {
            if (future.isCompletedExceptionally()) {
                return;
            }
            final KubernetesEndpointGroup kubernetesEndpointGroup = future.join();
            if (kubernetesEndpointGroup.whenReady().isCompletedExceptionally()) {
                return;
            }

            final LocalityLbEndpoints.Builder localityLbEndpointsBuilder = LocalityLbEndpoints.newBuilder();
            if (kubernetesLocalityLbEndpoints.hasLocality()) {
                localityLbEndpointsBuilder.setLocality(kubernetesLocalityLbEndpoints.getLocality());
            }
            if (kubernetesLocalityLbEndpoints.hasLoadBalancingWeight()) {
                localityLbEndpointsBuilder.setLoadBalancingWeight(
                        kubernetesLocalityLbEndpoints.getLoadBalancingWeight());
            }
            localityLbEndpointsBuilder.setPriority(kubernetesLocalityLbEndpoints.getPriority());
            for (com.linecorp.armeria.client.Endpoint endpoint : kubernetesEndpointGroup.endpoints()) {
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
            }
            clusterLoadAssignmentBuilder.addEndpoints(localityLbEndpointsBuilder.build());
        }

        // Called by the executorService.
        void close() {
            closing = true;
            if (scheduledFuture != null) {
                scheduledFuture.cancel(true);
            }
            kubernetesEndpointGroupFutures.forEach(
                    future -> future.thenAccept(KubernetesEndpointGroup::closeAsync));
        }
    }
}
