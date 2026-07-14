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
import static com.linecorp.centraldogma.server.internal.storage.InternalProjectConstants.INTERNAL_PROJECT_XDS;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.LEGACY_RESOURCE_ID_PATTERN_STRING;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.RESOURCE_ID_PATTERN;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.fileName;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.removePrefix;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
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

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * A gRPC service that handles Kubernetes resources.
 */
public final class XdsKubernetesService extends XdsKubernetesServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(XdsKubernetesService.class);

    @Nullable
    private static final KubernetesNodeIpExtractor nodeIpExtractor;

    static {
        final List<KubernetesNodeIpExtractor> extractors =
                ImmutableList.copyOf(ServiceLoader.load(KubernetesNodeIpExtractor.class,
                                                        XdsKubernetesService.class.getClassLoader()));
        if (!extractors.isEmpty()) {
            final KubernetesNodeIpExtractor extractor = extractors.get(0);
            if (extractors.size() > 1) {
                logger.warn("Found {} {}s. The first Node IP extractor found will be using among {}",
                            extractors.size(), KubernetesNodeIpExtractor.class.getSimpleName(), extractors);
            } else {
                logger.info("Using {} as a {}", extractor, KubernetesNodeIpExtractor.class.getSimpleName());
            }
            nodeIpExtractor = extractor;
        } else {
            nodeIpExtractor = null;
        }
    }

    public static final String K8S_ENDPOINT_AGGREGATORS_DIRECTORY = "/k8s/endpointAggregators/";
    public static final Pattern AGGREGATORS_REPLCACE_PATTERN =
            Pattern.compile("(?<=/k8s)/endpointAggregators/");

    public static final Pattern K8S_ENDPOINT_AGGREGATORS_NAME_PATTERN = Pattern.compile(
            "^groups/([^/]+)" + K8S_ENDPOINT_AGGREGATORS_DIRECTORY +
            '(' + LEGACY_RESOURCE_ID_PATTERN_STRING + ")$");

    public static final CompletableFuture<?>[] EMPTY_FUTURES = new CompletableFuture[0];

    private static final long PREVIEW_TIMEOUT_SECONDS = 5;

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
        xdsResourceManager.checkWritePermission(group);
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
        final String fileName = K8S_ENDPOINT_AGGREGATORS_DIRECTORY + aggregatorId + ".yaml";
        final String createSummary =
                isNullOrEmpty(request.getSummary()) ? "Create kubernetes endpoint: " + kubernetesEndpointName
                                                    : request.getSummary();
        validateKubernetesEndpointAndPush(
                responseObserver, kubernetesLocalityLbEndpointsList, group, fileName,
                () -> xdsResourceManager.push(
                        responseObserver, group, kubernetesEndpointName,
                        fileName, createSummary, aggregator, author, true));
    }

    private void validateKubernetesEndpointAndPush(
            StreamObserver<KubernetesEndpointAggregator> responseObserver,
            List<KubernetesLocalityLbEndpoints> kubernetesLocalityLbEndpointsList,
            String group, String fileName, Runnable onSuccess) {
        for (KubernetesLocalityLbEndpoints kubernetesLocalityLbEndpoints : kubernetesLocalityLbEndpointsList) {
            validateMetadataMappings(kubernetesLocalityLbEndpoints.getWatcher());
        }
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
                                                  group, fileName, false);
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

    private static void validateMetadataMappings(ServiceEndpointWatcher watcher) {
        for (MetadataMapping mapping : watcher.getMetadataMappingList()) {
            if (mapping.getResourceType() == MetadataMapping.ResourceType.RESOURCE_TYPE_UNSPECIFIED) {
                throw Status.INVALID_ARGUMENT.withDescription(
                        "resource_type must be specified in metadata_mapping: " + mapping)
                                             .asRuntimeException();
            }
            if (mapping.getEntryType() == MetadataMapping.EntryType.ENTRY_TYPE_UNSPECIFIED) {
                throw Status.INVALID_ARGUMENT.withDescription(
                        "entry_type must be specified in metadata_mapping: " + mapping)
                                             .asRuntimeException();
            }
            switch (mapping.getSourceCase()) {
                case SOURCE_KEY:
                    if (mapping.getSourceKey().isEmpty()) {
                        throw Status.INVALID_ARGUMENT.withDescription(
                                "source_key must not be empty in metadata_mapping: " + mapping)
                                                     .asRuntimeException();
                    }
                    break;
                case SOURCE_KEY_PREFIX:
                    if (mapping.getSourceKeyPrefix().isEmpty()) {
                        throw Status.INVALID_ARGUMENT.withDescription(
                                "source_key_prefix must not be empty in metadata_mapping: " + mapping)
                                                     .asRuntimeException();
                    }
                    break;
                default:
                    throw Status.INVALID_ARGUMENT.withDescription(
                            "either source_key or source_key_prefix must be set in metadata_mapping: " +
                            mapping).asRuntimeException();
            }
        }
    }

    /**
     * Creates a {@link KubernetesEndpointGroup} from the specified {@link ServiceEndpointWatcher}.
     */
    public static CompletableFuture<KubernetesEndpointGroup> createKubernetesEndpointGroup(
            ServiceEndpointWatcher watcher, MetaRepository metaRepository, String group, String fileName,
            boolean logIfFail) {
        final Kubeconfig kubeconfig = watcher.getKubeconfig();
        final String serviceName = watcher.getServiceName();

        final CompletableFuture<KubernetesEndpointGroup> future =
                toConfig(kubeconfig, metaRepository, group, fileName).thenApply(config -> {
                    final KubernetesEndpointGroupBuilder kubernetesEndpointGroupBuilder =
                            KubernetesEndpointGroup.builder(config).serviceName(serviceName);
                    if (!isNullOrEmpty(kubeconfig.getNamespace())) {
                        kubernetesEndpointGroupBuilder.namespace(kubeconfig.getNamespace());
                    }
                    if (!isNullOrEmpty(watcher.getPortName())) {
                        kubernetesEndpointGroupBuilder.portName(watcher.getPortName());
                    }
                    if (nodeIpExtractor != null) {
                        kubernetesEndpointGroupBuilder.nodeIpExtractor(
                                node -> nodeIpExtractor.extract(watcher, node));
                    }
                    return kubernetesEndpointGroupBuilder.build();
                });
        if (logIfFail) {
            future.exceptionally(cause -> {
                logger.warn("Failed to create KubernetesEndpointGroup. watcher: {}", watcher, cause);
                return null;
            });
        }

        return future;
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
        metaRepository.credential(credentialName(INTERNAL_PROJECT_XDS, group, credentialId))
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
                              metaRepository.credential(credentialName(INTERNAL_PROJECT_XDS, credentialId))
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
        xdsResourceManager.checkWritePermission(group);
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
        final String updateSummary =
                isNullOrEmpty(request.getSummary()) ? "Update kubernetes endpoint aggregator: " + aggregatorName
                                                    : request.getSummary();
        validateKubernetesEndpointAndPush(
                responseObserver, kubernetesLocalityLbEndpointsList, group, fileName(group, aggregatorName),
                () -> xdsResourceManager.update(
                        responseObserver, group, aggregatorName, updateSummary, aggregator0, author));
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
        xdsResourceManager.checkWritePermission(group);
        final String deleteSummary =
                isNullOrEmpty(request.getSummary()) ? "Delete kubernetes endpoint aggregator: " + aggregatorName
                                                    : request.getSummary();
        xdsResourceManager.delete(responseObserver, group, aggregatorName, deleteSummary, currentAuthor());
    }

    @Blocking
    @Override
    public void previewKubernetesEndpointAggregator(
            PreviewKubernetesEndpointAggregatorRequest request,
            StreamObserver<ClusterLoadAssignment> responseObserver) {
        final String parent = request.getParent();
        final String group = removePrefix("groups/", parent);
        xdsResourceManager.checkWritePermission(group);

        final KubernetesEndpointAggregator aggregator = request.getKubernetesEndpointAggregator();
        final List<KubernetesLocalityLbEndpoints> localityLbEndpointsList =
                aggregator.getLocalityLbEndpointsList();
        if (localityLbEndpointsList.isEmpty()) {
            throw Status.INVALID_ARGUMENT.withDescription("kubernetes locality lb endpoints are empty.")
                                         .asRuntimeException();
        }

        final MetaRepository metaRepository = xdsResourceManager.xdsProject().metaRepo();
        final ContextAwareBlockingTaskExecutor taskExecutor =
                ServiceRequestContext.current().blockingTaskExecutor();

        final List<CompletableFuture<LocalityLbEndpoints>> futures = new ArrayList<>();
        for (KubernetesLocalityLbEndpoints localityLbEndpoints : localityLbEndpointsList) {
            futures.add(resolvePreview(localityLbEndpoints, group, metaRepository, taskExecutor));
        }

        CompletableFuture.allOf(futures.toArray(EMPTY_FUTURES))
                        .handle((unused, cause) -> {
                            if (cause != null) {
                                final Throwable peeled = Exceptions.peel(cause);
                                final Status status =
                                        peeled instanceof IllegalArgumentException ||
                                        peeled instanceof EntryNotFoundException ?
                                        Status.INVALID_ARGUMENT : Status.INTERNAL;
                                responseObserver.onError(
                                        status.withDescription(peeled.getMessage())
                                              .asRuntimeException());
                            } else {
                                final ClusterLoadAssignment.Builder cla =
                                        ClusterLoadAssignment.newBuilder();
                                if (!aggregator.getClusterName().isEmpty()) {
                                    cla.setClusterName(aggregator.getClusterName());
                                }
                                for (CompletableFuture<LocalityLbEndpoints> future : futures) {
                                    cla.addEndpoints(future.join());
                                }
                                responseObserver.onNext(cla.build());
                                responseObserver.onCompleted();
                            }
                            return null;
                        });
    }

    private static CompletableFuture<LocalityLbEndpoints> resolvePreview(
            KubernetesLocalityLbEndpoints localityLbEndpoints, String group,
            MetaRepository metaRepository, ContextAwareBlockingTaskExecutor taskExecutor) {
        final CompletableFuture<LocalityLbEndpoints> result = new CompletableFuture<>();
        final ServiceEndpointWatcher watcher = localityLbEndpoints.getWatcher();
        createKubernetesEndpointGroup(watcher, metaRepository, group, "(preview)", false)
                .handle((endpointGroup, cause) -> {
                    if (cause != null) {
                        result.completeExceptionally(Exceptions.peel(cause));
                        return null;
                    }
                    final AtomicBoolean completed = new AtomicBoolean();
                    final ScheduledFuture<?> timeout = taskExecutor.schedule(() -> {
                        if (completed.compareAndSet(false, true)) {
                            endpointGroup.closeAsync();
                            result.completeExceptionally(new IllegalStateException(
                                    "Timed out after " + PREVIEW_TIMEOUT_SECONDS +
                                    "s resolving '" + watcher.getServiceName() + '\''));
                        }
                    }, PREVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    endpointGroup.whenReady().handle((endpoints, readyCause) -> {
                        if (!completed.compareAndSet(false, true)) {
                            return null;
                        }
                        timeout.cancel(false);
                        try {
                            if (readyCause != null) {
                                result.completeExceptionally(Exceptions.peel(readyCause));
                            } else {
                                result.complete(toLocalityLbEndpoints(endpointGroup,
                                                                       localityLbEndpoints));
                            }
                        } finally {
                            endpointGroup.closeAsync();
                        }
                        return null;
                    });
                    return null;
                });
        return result;
    }

    private static LocalityLbEndpoints toLocalityLbEndpoints(
            KubernetesEndpointGroup endpointGroup, KubernetesLocalityLbEndpoints localityLbEndpoints) {
        final LocalityLbEndpoints.Builder builder = LocalityLbEndpoints.newBuilder();
        if (localityLbEndpoints.hasLocality()) {
            builder.setLocality(localityLbEndpoints.getLocality());
        }
        if (localityLbEndpoints.hasLoadBalancingWeight()) {
            builder.setLoadBalancingWeight(localityLbEndpoints.getLoadBalancingWeight());
        }
        builder.setPriority(localityLbEndpoints.getPriority());
        KubernetesEndpointConverter.addLbEndpoints(builder, endpointGroup.endpoints(),
                                                   localityLbEndpoints.getWatcher());
        return builder.build();
    }
}
