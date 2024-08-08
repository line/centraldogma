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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.currentAuthor;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.removePrefix;
import static io.fabric8.kubernetes.client.Config.KUBERNETES_DISABLE_AUTO_CONFIG_SYSTEM_PROPERTY;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroup;
import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroupBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.xds.internal.XdsResourceManager;
import com.linecorp.centraldogma.xds.k8s.v1.CreateWatcherRequest;
import com.linecorp.centraldogma.xds.k8s.v1.KubernetesConfig;
import com.linecorp.centraldogma.xds.k8s.v1.UpdateWatcherRequest;
import com.linecorp.centraldogma.xds.k8s.v1.Watcher;
import com.linecorp.centraldogma.xds.k8s.v1.XdsKubernetesServiceGrpc.XdsKubernetesServiceImplBase;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class XdsKubernetesService extends XdsKubernetesServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(XdsKubernetesService.class);

    public static final String K8S_WATCHERS_DIRECTORY = "/k8s/watchers/";

    private final XdsResourceManager xdsResourceManager;

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
        final KubernetesEndpointGroup kubernetesEndpointGroup =
                createKubernetesEndpointGroup(request.getWatcher());
        final Author author = currentAuthor();
        final AtomicBoolean completed = new AtomicBoolean();
        kubernetesEndpointGroup.whenReady().handle((endpoints, cause) -> {
            if (!completed.compareAndSet(false, true)) {
                return null;
            }
            if (cause != null) {
                // Specific types.
                responseObserver.onError(Status.INTERNAL.withCause(cause).asRuntimeException());
                return null;
            }
            logger.debug("Successfully retrieved k8s endpoints: {}", endpoints);
            kubernetesEndpointGroup.closeAsync();
            final String watcherName = parent + K8S_WATCHERS_DIRECTORY + request.getWatcherId();
            final Watcher watcher = request.getWatcher().toBuilder().setName(watcherName).build();
            xdsResourceManager.push(responseObserver, group,
                                    K8S_WATCHERS_DIRECTORY + request.getWatcherId() + ".json",
                                    "Create watcher: " + watcherName, watcher, author);
            return null;
        });

        final ServiceRequestContext ctx = ServiceRequestContext.current();
        ctx.whenRequestCancelling().thenAccept(throwable -> {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            kubernetesEndpointGroup.closeAsync();
            responseObserver.onError(throwable);
        });
    }

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
        super.updateWatcher(request, responseObserver);
    }
}
