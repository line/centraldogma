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
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.currentAuthor;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.RESOURCE_ID_PATTERN;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.RESOURCE_ID_PATTERN_STRING;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.fileName;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.removePrefix;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
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
import com.linecorp.armeria.common.ContextAwareBlockingTaskExecutor;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.server.internal.credential.AccessTokenCredential;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
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

    static final String K8S_ENDPOINT_AGGREGATORS_DIRECTORY = "/k8s/endpointAggregators/";
    public static final Pattern AGGREGATORS_REPLCACE_PATTERN =
            Pattern.compile("(?<=/k8s)/endpointAggregators/");

    public static final Pattern K8S_ENDPOINT_AGGREGATORS_NAME_PATTERN = Pattern.compile(
            "^groups/([^/]+)" + K8S_ENDPOINT_AGGREGATORS_DIRECTORY + '(' + RESOURCE_ID_PATTERN_STRING + ")$");

    public static final CompletableFuture<?>[] EMPTY_FUTURES = new CompletableFuture[0];

    private final XdsResourceManager xdsResourceManager;

    /**
     * Creates a new instance.
     */
    public XdsKubernetesService(XdsResourceManager xdsResourceManager) {
        this.xdsResourceManager = xdsResourceManager;
    }

    @Blocking
    @Override
    public void createKubernetesEndpointAggregator(
            CreateKubernetesEndpointAggregatorRequest request,
            StreamObserver<KubernetesEndpointAggregator> responseObserver) {
        final String parent = request.getParent();
        final String group = removePrefix("groups/", parent);
        xdsResourceManager.checkGroup(group);
        final String aggregatorId = request.getAggregatorId();
        if (!RESOURCE_ID_PATTERN.matcher(aggregatorId).matches()) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid aggregator_id: " + aggregatorId +
                                                          " (expected: " + RESOURCE_ID_PATTERN + ')')
                                         .asRuntimeException();
        }

        final String kubernetesEndpointName = parent + K8S_ENDPOINT_AGGREGATORS_DIRECTORY + aggregatorId;
        final String clusterName = parent + "/k8s/clusters/" + aggregatorId;
        final KubernetesEndpointAggregator aggregator = request.getKubernetesEndpointAggregator().toBuilder()
                                                               .setName(kubernetesEndpointName)
                                                               .setClusterName(clusterName)
                                                               .build();
        final List<KubernetesLocalityLbEndpoints> kubernetesLocalityLbEndpointsList =
                aggregator.getLocalityLbEndpointsList();
        if (kubernetesLocalityLbEndpointsList.isEmpty()) {
            throw Status.INVALID_ARGUMENT.withDescription("kubernetes locality lb endpoints are empty.")
                                         .asRuntimeException();
        }
        final Author author = currentAuthor();
        final String fileName = K8S_ENDPOINT_AGGREGATORS_DIRECTORY + aggregatorId + ".json";
        validateKubernetesEndpointAndPush(
                responseObserver, kubernetesLocalityLbEndpointsList, group, fileName,
                () -> xdsResourceManager.push(
                        responseObserver, group, kubernetesEndpointName,
                        fileName,
                        "Create kubernetes endpoint: " + kubernetesEndpointName, aggregator, author, true));
    }

    private void validateKubernetesEndpointAndPush(
            StreamObserver<KubernetesEndpointAggregator> responseObserver,
            List<KubernetesLocalityLbEndpoints> kubernetesLocalityLbEndpointsList,
            String group, String fileName, Runnable onSuccess) {
        // Create a KubernetesEndpointGroup to check if the watcher is valid.
        // We use KubernetesEndpointGroup for simplicity, but we will implement a custom implementation
        // for better debugging and error handling in the future.
        final ContextAwareBlockingTaskExecutor taskExecutor =
                ServiceRequestContext.current().blockingTaskExecutor();

        final ArrayList<CompletableFuture<Void>> futures = new ArrayList<>();
        for (KubernetesLocalityLbEndpoints kubernetesLocalityLbEndpoints : kubernetesLocalityLbEndpointsList) {
            final CompletableFuture<Void> future = new CompletableFuture<>();
            futures.add(future);

            final ServiceEndpointWatcher watcher = kubernetesLocalityLbEndpoints.getWatcher();
            final CompletableFuture<KubernetesEndpointGroup> endpointGroupFuture =
                    createKubernetesEndpointGroup(watcher, xdsResourceManager.xdsProject().metaRepo(),
                                                  group, fileName);
            endpointGroupFuture.handle((kubernetesEndpointGroup, cause) -> {
                if (cause != null) {
                    cause = Exceptions.peel(cause);
                    if (cause instanceof IllegalArgumentException || cause instanceof EntryNotFoundException) {
                        future.completeExceptionally(Status.INVALID_ARGUMENT.withCause(cause)
                                                                            .withDescription(cause.getMessage())
                                                                            .asRuntimeException());
                    } else {
                        future.completeExceptionally(Status.INTERNAL.withCause(cause).asRuntimeException());
                    }
                    return null;
                }
                final AtomicBoolean completed = new AtomicBoolean();
                final CompletableFuture<List<Endpoint>> whenReady = kubernetesEndpointGroup.whenReady();
                // Use a schedule to time out the watcher creation until we implement a custom implementation.
                final ScheduledFuture<?> scheduledFuture = taskExecutor.schedule(() -> {
                    if (!completed.compareAndSet(false, true)) {
                        return;
                    }
                    kubernetesEndpointGroup.closeAsync();
                    future.completeExceptionally(
                            Status.INTERNAL.withDescription(
                                    "Failed to retrieve k8s endpoints within 5 seconds. watcher: " +
                                    watcher).asRuntimeException());
                }, 5, TimeUnit.SECONDS);

                whenReady.handle((endpoints, cause1) -> {
                    if (!completed.compareAndSet(false, true)) {
                        return null;
                    }
                    scheduledFuture.cancel(false);
                    kubernetesEndpointGroup.closeAsync();
                    if (cause1 != null) {
                        // Specific types.
                        responseObserver.onError(
                                Status.INTERNAL.withDescription("Failed to retrieve k8s endpoints")
                                               .withCause(cause1).asRuntimeException());
                        return null;
                    }
                    logger.debug("Successfully retrieved k8s endpoints: {}, watcher: {}", endpoints, watcher);
                    future.complete(null);
                    return null;
                });
                return null;
            });
        }

        final CompletableFuture<Void> allOfFuture =
                CompletableFuture.allOf(futures.toArray(EMPTY_FUTURES));
        allOfFuture.handle((unused, cause) -> {
            if (cause != null) {
                responseObserver.onError(cause);
            } else {
                onSuccess.run();
            }
            return null;
        });
    }

    /**
     * Creates a {@link KubernetesEndpointGroup} from the specified {@link ServiceEndpointWatcher}.
     * This method must be executed in a blocking thread because
     * {@link KubernetesEndpointGroupBuilder#build()} blocks the execution thread.
     */
    public static CompletableFuture<KubernetesEndpointGroup> createKubernetesEndpointGroup(
            ServiceEndpointWatcher watcher, MetaRepository metaRepository, String group, String fileName) {
        final Kubeconfig kubeconfig = watcher.getKubeconfig();
        final String serviceName = watcher.getServiceName();

        return toConfig(kubeconfig, metaRepository, group, fileName).thenApply(config -> {
            final KubernetesEndpointGroupBuilder kubernetesEndpointGroupBuilder =
                    KubernetesEndpointGroup.builder(config).serviceName(serviceName);
            if (!isNullOrEmpty(kubeconfig.getNamespace())) {
                kubernetesEndpointGroupBuilder.namespace(kubeconfig.getNamespace());
            }
            if (!isNullOrEmpty(watcher.getPortName())) {
                kubernetesEndpointGroupBuilder.portName(watcher.getPortName());
            }
            return kubernetesEndpointGroupBuilder.build();
        });
    }

    private static CompletableFuture<Config> toConfig(Kubeconfig kubeconfig, MetaRepository metaRepository,
                                                      String group, String fileName) {
        final ConfigBuilder configBuilder = new ConfigBuilder()
                .withMasterUrl(kubeconfig.getControlPlaneUrl())
                .withTrustCerts(kubeconfig.getTrustCerts());

        final String credentialId = kubeconfig.getCredentialId();
        if (isNullOrEmpty(credentialId)) {
            return CompletableFuture.completedFuture(configBuilder.build());
        }
        final CompletableFuture<Config> future = new CompletableFuture<>();
        // xDS only support repository credential so try using it first.
        metaRepository.credential(credentialName(XDS_CENTRAL_DOGMA_PROJECT, group, credentialId))
                      .thenAccept(credential -> {
                          if (!(credential instanceof AccessTokenCredential)) {
                              future.completeExceptionally(new IllegalArgumentException(
                                      "credential must be an access token: " + credential.withoutSecret()));
                          } else {
                              future.complete(configBuilder.withOauthToken(
                                      ((AccessTokenCredential) credential).accessToken()).build());
                          }
                      })
                      .exceptionally(cause -> {
                          final Throwable peeled = Exceptions.peel(cause);
                          if (peeled instanceof EntryNotFoundException) {
                              // Try to use the legacy project credential for backward compatibility.
                              metaRepository.credential(credentialName(XDS_CENTRAL_DOGMA_PROJECT, credentialId))
                                            .handle((credential, cause1) -> {
                                                if (cause1 != null) {
                                                    future.completeExceptionally(cause1);
                                                    return null;
                                                }
                                                if (!(credential instanceof AccessTokenCredential)) {
                                                    future.completeExceptionally(new IllegalArgumentException(
                                                            "credential must be an access token: " +
                                                            credential.withoutSecret()));
                                                } else {
                                                    logger.warn("Project credential is used in {}. " +
                                                                "Please migrate to group {}. credential ID: {}",
                                                                fileName, group, credentialId);
                                                    final String token =
                                                            ((AccessTokenCredential) credential).accessToken();
                                                    future.complete(
                                                            configBuilder.withOauthToken(token).build());
                                                }
                                                return null;
                                            });
                          } else {
                              future.completeExceptionally(peeled);
                          }
                          return null;
                      });
        return future;
    }

    @Blocking
    @Override
    public void updateKubernetesEndpointAggregator(
            UpdateKubernetesEndpointAggregatorRequest request,
            StreamObserver<KubernetesEndpointAggregator> responseObserver) {
        final KubernetesEndpointAggregator aggregator = request.getKubernetesEndpointAggregator();
        final String aggregatorName = aggregator.getName();
        final String group = checkAggregatorName(aggregatorName).group(1);
        xdsResourceManager.checkGroup(group);
        final List<KubernetesLocalityLbEndpoints> kubernetesLocalityLbEndpointsList =
                aggregator.getLocalityLbEndpointsList();
        if (kubernetesLocalityLbEndpointsList.isEmpty()) {
            throw Status.INVALID_ARGUMENT.withDescription("kubernetes locality lb endpoints are empty.")
                                         .asRuntimeException();
        }

        // Update the cluster name just in case it's mistakenly set by the user.
        final KubernetesEndpointAggregator aggregator0 = aggregator.toBuilder().setClusterName(
                AGGREGATORS_REPLCACE_PATTERN.matcher(aggregatorName).replaceFirst("/clusters/")).build();
        final Author author = currentAuthor();
        validateKubernetesEndpointAndPush(
                responseObserver, kubernetesLocalityLbEndpointsList, group, fileName(group, aggregatorName),
                () -> xdsResourceManager.update(
                        responseObserver, group, aggregatorName,
                        "Update kubernetes endpoint aggregator: " + aggregatorName, aggregator0, author));
    }

    private static Matcher checkAggregatorName(String aggregatorName) {
        final Matcher matcher = K8S_ENDPOINT_AGGREGATORS_NAME_PATTERN.matcher(aggregatorName);
        if (!matcher.matches()) {
            throw Status.INVALID_ARGUMENT.withDescription(
                                "Invalid kubernetes endpoint aggregator name: " + aggregatorName +
                                " (expected: " + K8S_ENDPOINT_AGGREGATORS_NAME_PATTERN + ')')
                                         .asRuntimeException();
        }
        return matcher;
    }

    @Override
    public void deleteKubernetesEndpointAggregator(DeleteKubernetesEndpointAggregatorRequest request,
                                                   StreamObserver<Empty> responseObserver) {
        final String aggregatorName = request.getName();
        final String group = checkAggregatorName(aggregatorName).group(1);
        xdsResourceManager.checkGroup(group);
        xdsResourceManager.delete(responseObserver, group, aggregatorName,
                                  "Delete kubernetes endpoint aggregator: " + aggregatorName,
                                  currentAuthor());
    }
}
