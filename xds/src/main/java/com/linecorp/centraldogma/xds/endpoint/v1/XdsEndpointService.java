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
package com.linecorp.centraldogma.xds.endpoint.v1;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.currentAuthor;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.CLUSTERS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.ENDPOINTS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.LEGACY_RESOURCE_ID_PATTERN_STRING;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.RESOURCE_ID_PATTERN;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.xds.internal.RequiresXdsGroupRole;
import com.linecorp.centraldogma.xds.internal.XdsResourceManager;

import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * Annotated service object for managing endpoints.
 */
public final class XdsEndpointService {

    private static final Pattern ENDPOINT_NAME_PATTERN =
            Pattern.compile("^groups/([^/]+)/endpoints/(" + LEGACY_RESOURCE_ID_PATTERN_STRING + ")$");

    private final XdsResourceManager xdsResourceManager;
    private final XdsEndpointUpdateScheduler xdsEndpointUpdateScheduler;

    /**
     * Creates a new instance.
     */
    public XdsEndpointService(XdsResourceManager xdsResourceManager,
                              ScheduledExecutorService controlPlaneExecutor) {
        this.xdsResourceManager = xdsResourceManager;
        xdsEndpointUpdateScheduler = new XdsEndpointUpdateScheduler(xdsResourceManager,
                                                                    controlPlaneExecutor);
    }

    /**
     * Returns the batch update task size.
     */
    public int batchUpdateTaskSize() {
        return xdsEndpointUpdateScheduler.batchUpdateTaskSize();
    }

    /**
     * POST /xds/groups/{group}/endpoints
     *
     * <p>Creates a new endpoint.
     */
    @Post("/xds/groups/{group}/endpoints")
    @Consumes("application/yaml")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> createEndpoint(
            @Param("group") String group,
            @Param("endpoint_id") String endpointId,
            @Param("summary") @Nullable String summary,
            String body) {
        if (!RESOURCE_ID_PATTERN.matcher(endpointId).matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid endpoint ID: " + endpointId));
        }
        final String endpointName = "groups/" + group + ENDPOINTS_DIRECTORY + endpointId;
        final String clusterName = "groups/" + group + CLUSTERS_DIRECTORY + endpointId;
        try {
            XdsResourceManager.parseYaml(body, ClusterLoadAssignment.newBuilder());
        } catch (IOException e) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid request body: " + e.getMessage()));
        }
        final String createSummary = isNullOrEmpty(summary) ? "Create endpoint: " + endpointName : summary;
        final String bodyToStore = XdsResourceManager.injectYamlField(body, "clusterName", clusterName);
        return xdsResourceManager.push(group, endpointName, ENDPOINTS_DIRECTORY + endpointId + ".yaml",
                                       createSummary, currentAuthor(), true, bodyToStore);
    }

    /**
     * PUT /xds/groups/{group}/endpoints/{endpoint_id}
     *
     * <p>Updates an existing endpoint.
     */
    @Put("/xds/groups/{group}/endpoints/{*endpoint_id}")
    @Consumes("application/yaml")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> updateEndpoint(
            @Param("group") String group,
            @Param("endpoint_id") String endpointId,
            @Param("summary") @Nullable String summary,
            String body) {
        final String endpointName = "groups/" + group + "/endpoints/" + endpointId;
        final Matcher matcher = ENDPOINT_NAME_PATTERN.matcher(endpointName);
        if (!matcher.matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid endpoint name: " + endpointName));
        }
        final String clusterName = "groups/" + group + CLUSTERS_DIRECTORY + endpointId;
        try {
            XdsResourceManager.parseYaml(body, ClusterLoadAssignment.newBuilder());
        } catch (IOException e) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid request body: " + e.getMessage()));
        }
        final String updateSummary = isNullOrEmpty(summary) ? "Update endpoint: " + endpointName : summary;
        final String bodyToStore = XdsResourceManager.injectYamlField(body, "clusterName", clusterName);
        return xdsResourceManager.update(group, endpointName, updateSummary, currentAuthor(), bodyToStore);
    }

    /**
     * DELETE /xds/groups/{group}/endpoints/{endpoint_id}
     *
     * <p>Removes an endpoint.
     */
    @Delete("/xds/groups/{group}/endpoints/{*endpoint_id}")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> deleteEndpoint(
            @Param("group") String group,
            @Param("endpoint_id") String endpointId,
            @Param("summary") @Nullable String summary) {
        final String endpointName = "groups/" + group + "/endpoints/" + endpointId;
        if (!ENDPOINT_NAME_PATTERN.matcher(endpointName).matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid endpoint name: " + endpointName));
        }
        final String deleteSummary = isNullOrEmpty(summary) ? "Delete endpoint: " + endpointName : summary;
        return xdsResourceManager.delete(group, endpointName, deleteSummary, currentAuthor());
    }

    /**
     * PATCH /xds/groups/{group}/endpoints/{endpointId}:registerLocalityLbEndpoint
     *
     * <p>Registers a locality lb endpoint into an existing endpoint resource.
     */
    // Named capture groups in Java regex do not allow underscores, so endpointId uses camelCase
    // here even though the rest of the path parameters follow snake_case (AIP-140).
    @Patch("regex:/xds/groups/(?<group>[^/]+)/endpoints/(?<endpointId>.+):registerLocalityLbEndpoint")
    @Consumes("application/yaml")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> registerLocalityLbEndpoint(
            @Param("group") String group,
            @Param("endpointId") String endpointId,
            String body) {
        final String endpointName = "groups/" + group + "/endpoints/" + endpointId;
        final Matcher matcher = ENDPOINT_NAME_PATTERN.matcher(endpointName);
        if (!matcher.matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid endpoint name: " + endpointName));
        }
        final LocalityLbEndpoint localityLbEndpoint;
        try {
            localityLbEndpoint = parseLocalityLbEndpoint(body);
        } catch (IOException e) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid request body: " + e.getMessage()));
        }
        final String fileName = ENDPOINTS_DIRECTORY + endpointId + ".yaml";
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        xdsEndpointUpdateScheduler.schedule(group, endpointName, fileName, localityLbEndpoint, future, true);
        return future;
    }

    /**
     * DELETE /xds/groups/{group}/endpoints/{endpointId}:deregisterLocalityLbEndpoint
     *
     * <p>Deregisters a locality lb endpoint from an existing endpoint resource.
     */
    // See the comment on registerLocalityLbEndpoint for why endpointId uses camelCase.
    @Delete("regex:/xds/groups/(?<group>[^/]+)/endpoints/(?<endpointId>.+):deregisterLocalityLbEndpoint")
    @Consumes("application/yaml")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> deregisterLocalityLbEndpoint(
            @Param("group") String group,
            @Param("endpointId") String endpointId,
            String body) {
        final String endpointName = "groups/" + group + "/endpoints/" + endpointId;
        final Matcher matcher = ENDPOINT_NAME_PATTERN.matcher(endpointName);
        if (!matcher.matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid endpoint name: " + endpointName));
        }
        final LocalityLbEndpoint localityLbEndpoint;
        try {
            localityLbEndpoint = parseLocalityLbEndpoint(body);
        } catch (IOException e) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid request body: " + e.getMessage()));
        }
        final String fileName = ENDPOINTS_DIRECTORY + endpointId + ".yaml";
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        xdsEndpointUpdateScheduler.schedule(group, endpointName, fileName, localityLbEndpoint, future,
                                            false);
        return future;
    }

    private static LocalityLbEndpoint parseLocalityLbEndpoint(String body) throws IOException {
        return XdsResourceManager.parseYaml(body, LocalityLbEndpoint.newBuilder());
    }
}
