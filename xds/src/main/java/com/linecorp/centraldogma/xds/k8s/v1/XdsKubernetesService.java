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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.currentAuthor;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.RESOURCE_ID_PATTERN;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.RESOURCE_ID_PATTERN_STRING;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.removePrefix;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Empty;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroup;
import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroupBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.xds.internal.XdsResourceManager;
import com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesServiceGrpc.XdsKubernetesServiceImplBase;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * A gRPC service that handles Kubernetes resources.
 */
public final class XdsKubernetesService extends XdsKubernetesServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(XdsKubernetesService.class);

    static final String K8S_WATCHERS_DIRECTORY = "/k8s/watchers/";
    public static final Pattern WATCHERS_REPLCACE_PATTERN = Pattern.compile("(?<=/k8s)/watchers/");

    public static final Pattern WATCHER_NAME_PATTERN = Pattern.compile(
            "^groups/([^/]+)" + K8S_WATCHERS_DIRECTORY + '(' + RESOURCE_ID_PATTERN_STRING + ")$");

    private final XdsResourceManager xdsResourceManager;

    /**
     * Creates a new instance.
     */
    public XdsKubernetesService(XdsResourceManager xdsResourceManager) {
        this.xdsResourceManager = xdsResourceManager;
    }

    @Blocking
    @Override
    public void createServiceEndpointWatcher(CreateServiceEndpointWatcherRequest request,
                                             StreamObserver<ServiceEndpointWatcher> responseObserver) {
        final String parent = request.getParent();
        final String group = removePrefix("groups/", parent);
        xdsResourceManager.checkGroup(group);
        final String watcherId = request.getWatcherId();
        if (!RESOURCE_ID_PATTERN.matcher(watcherId).matches()) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid watcher_id: " + watcherId +
                                                          " (expected: " + RESOURCE_ID_PATTERN + ')')
                                         .asRuntimeException();
        }

        final String watcherName = parent + K8S_WATCHERS_DIRECTORY + watcherId;
        final String clusterName = parent + "/k8s/clusters/" + watcherId;
        final ServiceEndpointWatcher watcher = request.getWatcher().toBuilder()
                                                      .setName(watcherName)
                                                      .setClusterName(clusterName)
                                                      .build();
        final Author author = currentAuthor();
        validateWatcherAndPush(responseObserver, watcher, () -> xdsResourceManager.push(
                responseObserver, group, K8S_WATCHERS_DIRECTORY + watcherId + ".json",
                "Create watcher: " + watcherName, watcher, author));
    }

    private static void validateWatcherAndPush(
            StreamObserver<ServiceEndpointWatcher> responseObserver,
            ServiceEndpointWatcher watcher, Runnable onSuccess) {
        // Create a KubernetesEndpointGroup to check if the watcher is valid.
        // We use KubernetesEndpointGroup for simplicity, but we will implement a custom implementation
        // for better debugging and error handling in the future.
        final KubernetesEndpointGroup kubernetesEndpointGroup = createKubernetesEndpointGroup(watcher);

        final AtomicBoolean completed = new AtomicBoolean();
        final CompletableFuture<List<Endpoint>> whenReady = kubernetesEndpointGroup.whenReady();
        final ServiceRequestContext ctx = ServiceRequestContext.current();

        // Use a schedule to time out the watcher creation until we implement a custom implementation.
        final ScheduledFuture<?> scheduledFuture = ctx.eventLoop().schedule(() -> {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            kubernetesEndpointGroup.closeAsync();
            responseObserver.onError(
                    Status.INTERNAL.withDescription(
                            "Failed to retrieve k8s endpoints within 5 seconds. watcherName: " +
                            watcher.getName()).asRuntimeException());
        }, 5, TimeUnit.SECONDS);

        whenReady.handle((endpoints, cause) -> {
            if (!completed.compareAndSet(false, true)) {
                return null;
            }
            scheduledFuture.cancel(false);
            kubernetesEndpointGroup.closeAsync();
            if (cause != null) {
                // Specific types.
                responseObserver.onError(Status.INTERNAL.withCause(cause).asRuntimeException());
                return null;
            }
            logger.debug("Successfully retrieved k8s endpoints: {}", endpoints);
            onSuccess.run();
            return null;
        });
    }

    /**
     * Creates a {@link KubernetesEndpointGroup} from the specified {@link ServiceEndpointWatcher}.
     * This method must be executed in a blocking thread because
     * {@link KubernetesEndpointGroupBuilder#build()} blocks the execution thread.
     */
    public static KubernetesEndpointGroup createKubernetesEndpointGroup(ServiceEndpointWatcher watcher) {
        final KubernetesConfig kubernetesConfig = watcher.getKubernetesConfig();
        final String serviceName = watcher.getServiceName();

        final KubernetesEndpointGroupBuilder kubernetesEndpointGroupBuilder =
                KubernetesEndpointGroup.builder(toConfig(kubernetesConfig)).serviceName(serviceName);
        if (!isNullOrEmpty(kubernetesConfig.getNamespace())) {
            kubernetesEndpointGroupBuilder.namespace(kubernetesConfig.getNamespace());
        }
        if (!isNullOrEmpty(watcher.getPortName())) {
            kubernetesEndpointGroupBuilder.portName(watcher.getPortName());
        }

        return kubernetesEndpointGroupBuilder.build();
    }

    private static Config toConfig(KubernetesConfig kubernetesConfig) {
        final ConfigBuilder configBuilder = new ConfigBuilder()
                .withMasterUrl(kubernetesConfig.getControlPlaneUrl())
                .withTrustCerts(kubernetesConfig.getTrustCerts());

        if (!isNullOrEmpty(kubernetesConfig.getOauthToken())) {
            configBuilder.withOauthToken(kubernetesConfig.getOauthToken());
        }

        return configBuilder.build();
    }

    @Blocking
    @Override
    public void updateServiceEndpointWatcher(UpdateServiceEndpointWatcherRequest request,
                                             StreamObserver<ServiceEndpointWatcher> responseObserver) {
        final ServiceEndpointWatcher watcher = request.getWatcher();
        final String watcherName = watcher.getName();
        final String group = checkWatcherName(watcherName).group(1);
        xdsResourceManager.checkGroup(group);

        // Update the cluster name just in case it's mistakenly set by the user.
        final ServiceEndpointWatcher watcher0 = watcher.toBuilder().setClusterName(
                WATCHERS_REPLCACE_PATTERN.matcher(watcherName).replaceFirst("/clusters/")).build();
        final Author author = currentAuthor();
        validateWatcherAndPush(responseObserver, watcher0, () -> xdsResourceManager.update(
                responseObserver, group, watcherName, "Update watcher: " + watcherName, watcher0, author));
    }

    private static Matcher checkWatcherName(String watcherName) {
        final Matcher matcher = WATCHER_NAME_PATTERN.matcher(watcherName);
        if (!matcher.matches()) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid watcher name: " + watcherName +
                                                          " (expected: " + WATCHER_NAME_PATTERN + ')')
                                         .asRuntimeException();
        }
        return matcher;
    }

    @Override
    public void deleteServiceEndpointWatcher(DeleteServiceEndpointWatcherRequest request,
                                             StreamObserver<Empty> responseObserver) {
        final String watcherName = request.getName();
        final String group = checkWatcherName(watcherName).group(1);
        xdsResourceManager.checkGroup(group);
        xdsResourceManager.delete(responseObserver, group, watcherName, "Delete watcher: " + watcherName,
                                  currentAuthor());
    }
}
