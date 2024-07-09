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
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroup;
import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroupBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.CommitResult;
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

    public static final MessageMarshaller JSON_MESSAGE_MARSHALLER =
            MessageMarshaller.builder().omittingInsignificantWhitespace(true)
                             .register(CreateWatcherRequest.getDefaultInstance())
                             .register(UpdateWatcherRequest.getDefaultInstance())
                             .build();

    private final CommandExecutor commandExecutor;

    public XdsKubernetesService(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    public static final String K8S_ENDPOINT_FETCH_CONFIG_DIRECTORY = "/k8s/endpoint-fetch-config/";

    // increase default timeout
    @Override
    public void createWatcher(CreateWatcherRequest request, StreamObserver<Watcher> responseObserver) {
        final String projectName = removePrefix("projects/", request.getProject());
        // TODO(minwoox): require write permission for the xDS project.
        final String xdsClusterName = request.getWatcherId();
        if (!xdsClusterName.startsWith(projectName + '/') ||
            xdsClusterName.length() <= projectName.length() + 1) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT
                            .withCause(new IllegalArgumentException(
                                    "xDS cluster name must start with the project name. project name: " +
                                    projectName + ", xDS cluster name: " + xdsClusterName))
                            .asRuntimeException());
            return;
        }
        final KubernetesEndpointGroup kubernetesEndpointGroup = createKubernetesEndpointGroup(request);
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
            logger.debug("Endpoints are ready: {}", endpoints);
            push(projectName, xdsClusterName, request, responseObserver);
            return null;
        });

        final ServiceRequestContext ctx = ServiceRequestContext.current();
        ctx.setRequestTimeoutMillis(Long.MAX_VALUE);
        ctx.whenRequestCancelling().thenAccept(throwable -> {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            kubernetesEndpointGroup.closeAsync();
            responseObserver.onError(throwable);
        });
    }

    private static String removePrefix(String prefix, String projectName) {
        assert projectName.startsWith(prefix);
        return projectName.substring(prefix.length());
    }

    public static KubernetesEndpointGroup createKubernetesEndpointGroup(CreateWatcherRequest request) {
        final Watcher watcher = request.getWatcher();
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

    private void push(String projectName, String xdsClusterName, CreateWatcherRequest request,
                      StreamObserver<Watcher> responseObserver) {
        final Change<JsonNode> change;
        try {
            change = Change.ofJsonUpsert(K8S_ENDPOINT_FETCH_CONFIG_DIRECTORY + xdsClusterName + ".json",
                                         JSON_MESSAGE_MARSHALLER.writeValueAsString(request));
        } catch (IOException e) {
            // Should never reach here.
            throw new Error(e);
        }
        final CompletableFuture<CommitResult> future = commandExecutor.execute(
                Command.push(Author.SYSTEM, XDS_CENTRAL_DOGMA_PROJECT, projectName,
                             Revision.HEAD, "Create watcher", "", Markup.PLAINTEXT, change));
        future.handle((result, cause) -> {
            if (cause != null) {
                responseObserver.onError(Status.INTERNAL.withCause(cause).asRuntimeException());
                return null;
            }

            responseObserver.onNext(Watcher.newBuilder()
                                            .setName("projects/" + projectName +
                                                     "/k8s/watchers/" + xdsClusterName)
                                            .setName(xdsClusterName)
                                           .build());
            responseObserver.onCompleted();
            return null;
        });
    }

    @Override
    public void updateWatcher(UpdateWatcherRequest request, StreamObserver<Watcher> responseObserver) {
        super.updateWatcher(request, responseObserver);
    }
}
