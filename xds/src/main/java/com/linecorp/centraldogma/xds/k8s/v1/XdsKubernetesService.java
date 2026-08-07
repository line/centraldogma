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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroup;
import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesEndpointGroupBuilder;
import com.linecorp.armeria.common.ContextAwareBlockingTaskExecutor;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.server.internal.credential.AccessTokenCredential;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.xds.internal.RequiresXdsGroupRole;
import com.linecorp.centraldogma.xds.internal.XdsResourceManager;

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;

/**
 * Annotated service object for managing Kubernetes endpoint aggregators.
 */
public final class XdsKubernetesService {

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

    /**
     * POST /xds/groups/{group}/k8s/endpointAggregators
     *
     * <p>Creates a new Kubernetes endpoint aggregator.
     */
    @Post("/xds/groups/{group}/k8s/endpointAggregators")
    @Consumes("application/yaml")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> createKubernetesEndpointAggregator(
            @Param("group") String group,
            @Param("aggregator_id") String aggregatorId,
            @Param("summary") @Nullable String summary,
            String body) {
        if (!RESOURCE_ID_PATTERN.matcher(aggregatorId).matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid aggregator ID: " + aggregatorId));
        }
        final String kubernetesEndpointName =
                "groups/" + group + K8S_ENDPOINT_AGGREGATORS_DIRECTORY + aggregatorId;
        final String clusterName = "groups/" + group + "/k8s/clusters/" + aggregatorId;
        final KubernetesEndpointAggregator aggregator;
        try {
            aggregator = XdsResourceManager.parseYaml(body, KubernetesEndpointAggregator.newBuilder());
        } catch (IOException e) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid request body: " + e.getMessage()));
        }
        final List<KubernetesLocalityLbEndpoints> kubernetesLocalityLbEndpointsList =
                aggregator.getLocalityLbEndpointsList();
        if (kubernetesLocalityLbEndpointsList.isEmpty()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "locality_lb_endpoints must not be empty"));
        }
        final Author author = currentAuthor();
        final String aggregatorFileName = K8S_ENDPOINT_AGGREGATORS_DIRECTORY + aggregatorId + ".yaml";
        final String createSummary = isNullOrEmpty(summary) ?
                                     "Create kubernetes endpoint: " + kubernetesEndpointName : summary;
        String bodyToStore = XdsResourceManager.injectYamlField(body, "name", kubernetesEndpointName);
        bodyToStore = XdsResourceManager.injectYamlField(bodyToStore, "clusterName", clusterName);
        final String finalBodyToStore = bodyToStore;
        return validateKubernetesEndpointAndPushHttp(
                kubernetesLocalityLbEndpointsList, group, aggregatorFileName,
                () -> xdsResourceManager.push(group, kubernetesEndpointName,
                                              aggregatorFileName, createSummary, author,
                                              true, finalBodyToStore));
    }

    /**
     * PUT /xds/groups/{group}/k8s/endpointAggregators/{aggregator_id}
     *
     * <p>Updates an existing Kubernetes endpoint aggregator.
     */
    @Blocking
    @Put("/xds/groups/{group}/k8s/endpointAggregators/{*aggregator_id}")
    @Consumes("application/yaml")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> updateKubernetesEndpointAggregator(
            @Param("group") String group,
            @Param("aggregator_id") String aggregatorId,
            @Param("summary") @Nullable String summary,
            String body) {
        final String aggregatorName = "groups/" + group + K8S_ENDPOINT_AGGREGATORS_DIRECTORY + aggregatorId;
        final Matcher matcher = K8S_ENDPOINT_AGGREGATORS_NAME_PATTERN.matcher(aggregatorName);
        if (!matcher.matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid aggregator name: " + aggregatorName));
        }
        final String clusterName = AGGREGATORS_REPLCACE_PATTERN.matcher(aggregatorName)
                                                               .replaceFirst("/clusters/");
        final KubernetesEndpointAggregator aggregator;
        try {
            aggregator = XdsResourceManager.parseYaml(body, KubernetesEndpointAggregator.newBuilder());
        } catch (IOException e) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid request body: " + e.getMessage()));
        }
        final List<KubernetesLocalityLbEndpoints> kubernetesLocalityLbEndpointsList =
                aggregator.getLocalityLbEndpointsList();
        if (kubernetesLocalityLbEndpointsList.isEmpty()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "locality_lb_endpoints must not be empty"));
        }
        final Author author = currentAuthor();
        final String updateSummary = isNullOrEmpty(summary) ?
                                     "Update kubernetes endpoint aggregator: " + aggregatorName : summary;
        // Both name and clusterName are server-derived; inject them so the stored YAML is always correct
        // regardless of what the client sent in the body.
        String bodyToStore = XdsResourceManager.injectYamlField(body, "name", aggregatorName);
        bodyToStore = XdsResourceManager.injectYamlField(bodyToStore, "clusterName", clusterName);
        final String finalBodyToStore = bodyToStore;
        return validateKubernetesEndpointAndPushHttp(
                kubernetesLocalityLbEndpointsList, group, fileName(group, aggregatorName),
                () -> xdsResourceManager.update(group, aggregatorName, updateSummary, author,
                                                finalBodyToStore));
    }

    /**
     * DELETE /xds/groups/{group}/k8s/endpointAggregators/{aggregator_id}
     *
     * <p>Removes a Kubernetes endpoint aggregator.
     */
    @Delete("/xds/groups/{group}/k8s/endpointAggregators/{*aggregator_id}")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> deleteKubernetesEndpointAggregator(
            @Param("group") String group,
            @Param("aggregator_id") String aggregatorId,
            @Param("summary") @Nullable String summary) {
        final String aggregatorName = "groups/" + group + K8S_ENDPOINT_AGGREGATORS_DIRECTORY + aggregatorId;
        if (!K8S_ENDPOINT_AGGREGATORS_NAME_PATTERN.matcher(aggregatorName).matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid aggregator name: " + aggregatorName));
        }
        final String deleteSummary = isNullOrEmpty(summary) ?
                                     "Delete kubernetes endpoint aggregator: " + aggregatorName : summary;
        return xdsResourceManager.delete(group, aggregatorName, deleteSummary, currentAuthor());
    }

    /**
     * POST /xds/groups/{group}/k8s/endpointAggregators:preview
     *
     * <p>Previews the endpoints that would be resolved for a Kubernetes endpoint aggregator
     * without persisting it.
     */
    @Post("/xds/groups/{group}/k8s/endpointAggregators:preview")
    @Consumes("application/yaml")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> previewKubernetesEndpointAggregator(
            @Param("group") String group,
            String body) {
        final KubernetesEndpointAggregator aggregator;
        try {
            aggregator = XdsResourceManager.parseYaml(
                    body, KubernetesEndpointAggregator.newBuilder());
        } catch (IOException e) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid request body: " + e.getMessage()));
        }
        final List<KubernetesLocalityLbEndpoints> localityLbEndpointsList =
                aggregator.getLocalityLbEndpointsList();
        if (localityLbEndpointsList.isEmpty()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "locality_lb_endpoints must not be empty"));
        }
        final MetaRepository metaRepository = xdsResourceManager.xdsProject().metaRepo();
        final ContextAwareBlockingTaskExecutor taskExecutor =
                ServiceRequestContext.current().blockingTaskExecutor();

        final List<CompletableFuture<LocalityLbEndpoints>> futures = new ArrayList<>();
        for (KubernetesLocalityLbEndpoints localityLbEndpoints : localityLbEndpointsList) {
            futures.add(resolvePreview(localityLbEndpoints, group, metaRepository, taskExecutor));
        }

        return CompletableFuture.allOf(futures.toArray(EMPTY_FUTURES))
                                .handle((unused, cause) -> {
                                    if (cause != null) {
                                        return toErrorHttpResponse(cause);
                                    }
                                    final ClusterLoadAssignment.Builder cla =
                                            ClusterLoadAssignment.newBuilder();
                                    if (!aggregator.getClusterName().isEmpty()) {
                                        cla.setClusterName(aggregator.getClusterName());
                                    }
                                    for (CompletableFuture<LocalityLbEndpoints> future : futures) {
                                        cla.addEndpoints(future.join());
                                    }
                                    try {
                                        return XdsResourceManager.toYamlResponse(cla.build());
                                    } catch (IOException e) {
                                        return XdsResourceManager.errorResponse(
                                                HttpStatus.INTERNAL_SERVER_ERROR, e);
                                    }
                                });
    }

    private CompletableFuture<HttpResponse> validateKubernetesEndpointAndPushHttp(
            List<KubernetesLocalityLbEndpoints> kubernetesLocalityLbEndpointsList,
            String group, String fileNameForLookup,
            Supplier<CompletableFuture<HttpResponse>> onSuccess) {
        for (KubernetesLocalityLbEndpoints kubernetesLocalityLbEndpoints : kubernetesLocalityLbEndpointsList) {
            try {
                validateMetadataMappings(kubernetesLocalityLbEndpoints.getWatcher());
            } catch (IllegalArgumentException e) {
                return CompletableFuture.completedFuture(
                        XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST, e));
            }
        }
        final ContextAwareBlockingTaskExecutor taskExecutor =
                ServiceRequestContext.current().blockingTaskExecutor();

        final ArrayList<CompletableFuture<Void>> futures = new ArrayList<>();
        for (KubernetesLocalityLbEndpoints kubernetesLocalityLbEndpoints : kubernetesLocalityLbEndpointsList) {
            final CompletableFuture<Void> future = new CompletableFuture<>();
            futures.add(future);

            final ServiceEndpointWatcher watcher = kubernetesLocalityLbEndpoints.getWatcher();
            final CompletableFuture<KubernetesEndpointGroup> endpointGroupFuture =
                    createKubernetesEndpointGroup(watcher, xdsResourceManager.xdsProject().metaRepo(),
                                                  group, fileNameForLookup, false);
            endpointGroupFuture.handle((kubernetesEndpointGroup, cause) -> {
                if (cause != null) {
                    future.completeExceptionally(Exceptions.peel(cause));
                    return null;
                }
                final AtomicBoolean completed = new AtomicBoolean();
                final CompletableFuture<List<Endpoint>> whenReady = kubernetesEndpointGroup.whenReady();
                final ScheduledFuture<?> scheduledFuture = taskExecutor.schedule(() -> {
                    if (!completed.compareAndSet(false, true)) {
                        return;
                    }
                    kubernetesEndpointGroup.closeAsync();
                    future.completeExceptionally(new IllegalStateException(
                            "Failed to retrieve k8s endpoints within 5 seconds. watcher: " + watcher));
                }, 5, TimeUnit.SECONDS);

                whenReady.handle((endpoints, cause1) -> {
                    if (!completed.compareAndSet(false, true)) {
                        return null;
                    }
                    scheduledFuture.cancel(false);
                    kubernetesEndpointGroup.closeAsync();
                    if (cause1 != null) {
                        future.completeExceptionally(
                                new IllegalStateException("Failed to retrieve k8s endpoints", cause1));
                    } else {
                        logger.debug("Successfully retrieved k8s endpoints: {}, watcher: {}",
                                     endpoints, watcher);
                        future.complete(null);
                    }
                    return null;
                });
                return null;
            });
        }

        return CompletableFuture.allOf(futures.toArray(EMPTY_FUTURES))
                                .<HttpResponse>handle((unused, cause) -> {
                                    if (cause != null) {
                                        return toErrorHttpResponse(cause);
                                    }
                                    return null;
                                })
                                .thenCompose(errorResponse -> {
                                    if (errorResponse != null) {
                                        return CompletableFuture.completedFuture(errorResponse);
                                    }
                                    return onSuccess.get();
                                });
    }

    private static HttpResponse toErrorHttpResponse(Throwable cause) {
        final Throwable peeled = Exceptions.peel(cause);
        // invalid credential not found
        if (peeled instanceof IllegalArgumentException || peeled instanceof EntryNotFoundException) {
            return XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST, peeled);
        }

        return XdsResourceManager.errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, peeled);
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
                                result.complete(toLocalityLbEndpoints(endpointGroup, localityLbEndpoints));
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

    private static void validateMetadataMappings(ServiceEndpointWatcher watcher) {
        for (MetadataMapping mapping : watcher.getMetadataMappingList()) {
            if (mapping.getResourceType() == MetadataMapping.ResourceType.RESOURCE_TYPE_UNSPECIFIED) {
                throw new IllegalArgumentException(
                        "resource_type must be specified in metadata_mapping: " + mapping);
            }
            if (mapping.getEntryType() == MetadataMapping.EntryType.ENTRY_TYPE_UNSPECIFIED) {
                throw new IllegalArgumentException(
                        "entry_type must be specified in metadata_mapping: " + mapping);
            }
            switch (mapping.getSourceCase()) {
                case SOURCE_KEY:
                    if (mapping.getSourceKey().isEmpty()) {
                        throw new IllegalArgumentException(
                                "source_key must not be empty in metadata_mapping: " + mapping);
                    }
                    break;
                case SOURCE_KEY_PREFIX:
                    if (mapping.getSourceKeyPrefix().isEmpty()) {
                        throw new IllegalArgumentException(
                                "source_key_prefix must not be empty in metadata_mapping: " + mapping);
                    }
                    break;
                default:
                    throw new IllegalArgumentException(
                            "either source_key or source_key_prefix must be set in metadata_mapping: " +
                            mapping);
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
}
