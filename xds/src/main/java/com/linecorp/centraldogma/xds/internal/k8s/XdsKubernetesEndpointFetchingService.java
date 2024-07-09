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
package com.linecorp.centraldogma.xds.internal.k8s;

import static com.linecorp.centraldogma.server.internal.ExecutorServiceUtil.terminate;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.K8S_ENDPOINTS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.k8s.XdsKubernetesService.K8S_ENDPOINT_FETCH_CONFIG_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.k8s.XdsKubernetesService.createKubernetesEndpointGroup;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroup;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;
import com.linecorp.centraldogma.xds.internal.JsonFormatUtil;
import com.linecorp.centraldogma.xds.internal.XdsProjectWatchingService;
import com.linecorp.centraldogma.xds.k8s.v1.CreateWatcherRequest;

import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.netty.util.concurrent.DefaultThreadFactory;

final class XdsKubernetesEndpointFetchingService extends XdsProjectWatchingService {

    private static final Logger logger = LoggerFactory.getLogger(XdsKubernetesEndpointFetchingService.class);

    private final MeterRegistry meterRegistry;

    // Only accessed by the executorService.
    private final Map<String, Map<String, KubernetesEndpointGroup>> kubernetesWatchers = new HashMap<>();

    private volatile CommandExecutor commandExecutor;
    @Nullable
    private volatile ScheduledExecutorService executorService;

    XdsKubernetesEndpointFetchingService(RepositoryManager repositoryManager, MeterRegistry meterRegistry) {
        super(repositoryManager);
        this.meterRegistry = meterRegistry;
    }

    boolean isStarted() {
        return executorService != null;
    }

    synchronized void start(CommandExecutor commandExecutor) {
        if (isStarted()) {
            return;
        }
        this.commandExecutor = requireNonNull(commandExecutor, "commandExecutor");
        final ScheduledExecutorService executorService = ExecutorServiceMetrics.monitor(
                meterRegistry, Executors.newSingleThreadScheduledExecutor(
                        new DefaultThreadFactory("k8s-executor", true)), "k8sExecutor");
        this.executorService = executorService;
        executorService.execute(this::start);
    }

    synchronized void stop() {
        final ExecutorService executorService = this.executorService;
        try {
            final boolean interrupted = terminate(executorService);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            this.executorService = null;
        }
    }

    @Override
    protected ScheduledExecutorService executor() {
        final ScheduledExecutorService executorService = this.executorService;
        assert executorService != null;
        return executorService;
    }

    @Override
    protected String pathPattern() {
        return K8S_ENDPOINT_FETCH_CONFIG_DIRECTORY + "**";
    }

    @Override
    protected void handleXdsResources(String path, String contentAsText, String repoName)
            throws InvalidProtocolBufferException {
        final CreateWatcherRequest.Builder createWatcherRequestBuilder = CreateWatcherRequest.newBuilder();
        try {
            XdsKubernetesService.JSON_MESSAGE_MARSHALLER.mergeValue(contentAsText, createWatcherRequestBuilder);
        } catch (IOException e) {
            logger.warn("Failed to parse a CreateWatcherRequest at {}{}. content: {}",
                        repoName, path, contentAsText, e);
            return;
        }
        final KubernetesEndpointGroup kubernetesEndpointGroup = createKubernetesEndpointGroup(
                createWatcherRequestBuilder.build());
        final Map<String, KubernetesEndpointGroup> watchers =
                kubernetesWatchers.computeIfAbsent(repoName, unused -> new HashMap<>());

        final String xdsClusterName = createWatcherRequestBuilder.getWatcherId();
        final KubernetesEndpointGroup oldWatcher = watchers.get(xdsClusterName);
        if (oldWatcher != null) {
            oldWatcher.closeAsync();
        }
        watchers.put(xdsClusterName, kubernetesEndpointGroup);
        kubernetesEndpointGroup.addListener(endpoints -> {
            if (endpoints.isEmpty()) {
                return;
            }
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
                    ClusterLoadAssignment.newBuilder().addEndpoints(localityLbEndpointsBuilder.build())
                                         .build();
            final String json;
            try {
                json = JsonFormatUtil.printer().print(clusterLoadAssignment);
            } catch (InvalidProtocolBufferException e) {
                // Should never reach here.
                throw new Error(e);
            }
            final String fileName = K8S_ENDPOINTS_DIRECTORY + clusterLoadAssignment.getClusterName() + ".json";
            final Change<JsonNode> change = Change.ofJsonUpsert(fileName, json);
            commandExecutor.execute(
                    Command.push(Author.SYSTEM, XDS_CENTRAL_DOGMA_PROJECT, repoName, Revision.HEAD,
                                 "Add " + fileName + " with " + endpoints.size() + " endpoints.", "",
                                 Markup.PLAINTEXT, change));
        });
    }

    @Override
    protected void onRepositoryRemoved(String repoName) {
        final Map<String, KubernetesEndpointGroup> watchers = kubernetesWatchers.remove(repoName);
        if (watchers != null) {
            watchers.values().forEach(KubernetesEndpointGroup::closeAsync);
        }
    }

    @Override
    protected void onFileRemoved(String repoName, String path) {
        final Map<String, KubernetesEndpointGroup> watchers = kubernetesWatchers.get(repoName);
        if (watchers != null) {
            final String clusterName =
                    repoName + '/' + path.substring(K8S_ENDPOINT_FETCH_CONFIG_DIRECTORY.length(),
                                                    path.length() - 5); // Remove .json
            final KubernetesEndpointGroup watcher = watchers.get(clusterName);
            if (watcher != null) {
                watcher.closeAsync();
            }
        }
    }

    @Override
    protected void onDiffHandled() {}

    @Override
    protected boolean isStopped() {
        return executorService == null;
    }
}
