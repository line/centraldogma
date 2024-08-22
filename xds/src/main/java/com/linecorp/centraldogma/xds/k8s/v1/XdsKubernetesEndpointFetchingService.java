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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;

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
    private final Map<String, Map<String, KubernetesEndpointGroup>> kubernetesWatchers = new HashMap<>();

    private final ScheduledExecutorService executorService;
    private volatile boolean stopped;

    XdsKubernetesEndpointFetchingService(Project xdsProject, CommandExecutor commandExecutor,
                                         MeterRegistry meterRegistry) {
        super(xdsProject);
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
            kubernetesWatchers.values().forEach(map -> {
                map.values().forEach(KubernetesEndpointGroup::closeAsync);
                map.clear();
            });
            kubernetesWatchers.clear();
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
        final KubernetesEndpointGroup kubernetesEndpointGroup = createKubernetesEndpointGroup(endpointWatcher);
        final Map<String, KubernetesEndpointGroup> watchers =
                kubernetesWatchers.computeIfAbsent(groupName, unused -> new HashMap<>());

        final String watcherName = endpointWatcher.getName();
        final KubernetesEndpointGroup oldWatcher = watchers.get(watcherName);
        if (oldWatcher != null) {
            oldWatcher.closeAsync();
        }
        watchers.put(watcherName, kubernetesEndpointGroup);
        kubernetesEndpointGroup.addListener(endpoints -> {
            if (endpoints.isEmpty()) {
                return;
            }
            executorService.execute(
                    () -> pushK8sEndpoints(kubernetesEndpointGroup, groupName, endpoints, endpointWatcher));
        }, true);
    }

    private void pushK8sEndpoints(KubernetesEndpointGroup kubernetesEndpointGroup, String groupName,
                                  List<com.linecorp.armeria.client.Endpoint> endpoints,
                                  ServiceEndpointWatcher watcher) {
        if (kubernetesEndpointGroup.isClosing()) {
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
                ClusterLoadAssignment.newBuilder()
                                     .setClusterName(watcher.getClusterName())
                                     .addEndpoints(localityLbEndpointsBuilder.build())
                                     .build();
        final String json;
        try {
            json = JSON_MESSAGE_MARSHALLER.writeValueAsString(clusterLoadAssignment);
        } catch (IOException e) {
            // Should never reach here.
            throw new Error(e);
        }
        final Matcher matcher = WATCHER_NAME_PATTERN.matcher(watcher.getName());
        final boolean matches = matcher.matches();
        assert matches;
        final String watcherId = matcher.group(2);
        final String fileName = K8S_ENDPOINTS_DIRECTORY + watcherId + ".json";
        final Change<JsonNode> change = Change.ofJsonUpsert(fileName, json);
        commandExecutor.execute(
                Command.push(Author.SYSTEM, XDS_CENTRAL_DOGMA_PROJECT, groupName, Revision.HEAD,
                             "Add " + watcher.getClusterName() + " with " + endpoints.size() + " endpoints.",
                             "", Markup.PLAINTEXT, change)).handle((unused, cause) -> {
            if (cause != null) {
                logger.warn("Failed to push {} to {}", change, groupName, cause);
            }
            return null;
        });
    }

    @Override
    protected void onGroupRemoved(String groupName) {
        final Map<String, KubernetesEndpointGroup> watchers = kubernetesWatchers.remove(groupName);
        if (watchers != null) {
            watchers.values().forEach(KubernetesEndpointGroup::closeAsync);
            watchers.clear();
        }
    }

    @Override
    protected void onFileRemoved(String groupName, String path) {
        final Map<String, KubernetesEndpointGroup> watchers = kubernetesWatchers.get(groupName);
        if (watchers != null) {
            final String watcherName =
                    "groups/" + groupName + path.substring(0, path.length() - 5); // Remove .json
            // e.g. groups/foo/k8s/watchers/foo-cluster
            final KubernetesEndpointGroup watcher = watchers.get(watcherName);
            if (watcher != null) {
                watcher.closeAsync();
            }
        }

        // Remove corresponding endpoints.
        final String endpointPath = WATCHERS_REPLCACE_PATTERN.matcher(path).replaceFirst("/endpoints/");
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
}
