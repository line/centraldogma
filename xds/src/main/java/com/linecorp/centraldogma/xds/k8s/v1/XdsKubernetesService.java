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
import static io.fabric8.kubernetes.client.Config.KUBERNETES_DISABLE_AUTO_CONFIG_SYSTEM_PROPERTY;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Empty;

import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroup;
import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroupBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.xds.internal.XdsResourceManager;
import com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesServiceGrpc.XdsKubernetesServiceImplBase;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * A gRPC service that handles Kubernetes resources.
 */
public final class XdsKubernetesService extends XdsKubernetesServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(XdsKubernetesService.class);

    static final String K8S_WATCHERS_DIRECTORY = "/k8s/watchers/";

    private static final Pattern WATCHER_NAME_PATTERN =
            Pattern.compile("^groups/([^/]+)" + K8S_WATCHERS_DIRECTORY + RESOURCE_ID_PATTERN_STRING + '$');

    private final XdsResourceManager xdsResourceManager;

    /**
     * Creates a new instance.
     */
    public XdsKubernetesService(XdsResourceManager xdsResourceManager) {
        this.xdsResourceManager = xdsResourceManager;
        System.setProperty(KUBERNETES_DISABLE_AUTO_CONFIG_SYSTEM_PROPERTY, "true");
    }

    //TODO(minwoox): increase timeout
    @Override
    public void createWatcher(CreateWatcherRequest request, StreamObserver<Watcher> responseObserver) {
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
        final Watcher watcher = request.getWatcher().toBuilder().setName(watcherName).build();
        final Author author = currentAuthor();
        validateWatcherAndPush(responseObserver, watcher, () -> xdsResourceManager.push(
                responseObserver, group, K8S_WATCHERS_DIRECTORY + watcherId + ".json",
                "Create watcher: " + watcherName, watcher, author));
    }

    private static void validateWatcherAndPush(
            StreamObserver<Watcher> responseObserver, Watcher watcher, Runnable onSuccess) {
        // Create a KubernetesEndpointGroup to check if the watcher is valid.
        final KubernetesEndpointGroup kubernetesEndpointGroup = createKubernetesEndpointGroup(watcher);

        final AtomicBoolean completed = new AtomicBoolean();
        kubernetesEndpointGroup.whenReady().handle((endpoints, cause) -> {
            if (!completed.compareAndSet(false, true)) {
                return null;
            }
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

        final ServiceRequestContext ctx = ServiceRequestContext.current();
        ctx.whenRequestCancelling().thenAccept(throwable -> {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            kubernetesEndpointGroup.closeAsync();
        });
    }

    /**
     * Creates a {@link KubernetesEndpointGroup} from the specified {@link Watcher}.
     */
    public static KubernetesEndpointGroup createKubernetesEndpointGroup(Watcher watcher) {
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

    @Override
    public void updateWatcher(UpdateWatcherRequest request, StreamObserver<Watcher> responseObserver) {
        final Watcher watcher = request.getWatcher();
        final String watcherName = watcher.getName();
        final String group = checkWatcherName(watcherName).group(1);
        xdsResourceManager.checkGroup(group);
        final Author author = currentAuthor();
        validateWatcherAndPush(responseObserver, watcher, () -> xdsResourceManager.update(
                responseObserver, group, watcherName, "Update watcher: " + watcherName, watcher, author));
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
    public void deleteWatcher(DeleteWatcherRequest request, StreamObserver<Empty> responseObserver) {
        final String watcherName = request.getName();
        final String group = checkWatcherName(watcherName).group(1);
        xdsResourceManager.checkGroup(group);
        xdsResourceManager.delete(responseObserver, group, watcherName, "Delete watcher: " + watcherName,
                                  currentAuthor());
    }
}
