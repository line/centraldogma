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
package com.linecorp.centraldogma.xds.cluster.v1;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.currentAuthor;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.CLUSTERS_DIRECTORY;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.LEGACY_RESOURCE_ID_PATTERN_STRING;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.RESOURCE_ID_PATTERN;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.xds.internal.RequiresXdsGroupRole;
import com.linecorp.centraldogma.xds.internal.XdsResourceManager;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;

/**
 * Annotated service object for managing clusters.
 */
public final class XdsClusterService {

    private static final Pattern CLUSTER_NAME_PATTERN =
            Pattern.compile("^groups/([^/]+)/clusters/" + LEGACY_RESOURCE_ID_PATTERN_STRING + '$');

    private final XdsResourceManager xdsResourceManager;

    /**
     * Creates a new instance.
     */
    public XdsClusterService(XdsResourceManager xdsResourceManager) {
        this.xdsResourceManager = xdsResourceManager;
    }

    /**
     * POST /xds/groups/{group}/clusters
     *
     * <p>Creates a new cluster.
     */
    @Post("/xds/groups/{group}/clusters")
    @Consumes("application/yaml")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> createCluster(
            @Param("group") String group,
            @Param("cluster_id") String clusterId,
            @Param("summary") @Nullable String summary,
            String body) {
        if (!RESOURCE_ID_PATTERN.matcher(clusterId).matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid cluster ID: " + clusterId));
        }
        final String clusterName = "groups/" + group + CLUSTERS_DIRECTORY + clusterId;
        try {
            XdsResourceManager.parseYaml(body, Cluster.newBuilder());
        } catch (IOException e) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid request body: " + e.getMessage()));
        }
        final String createSummary = isNullOrEmpty(summary) ? "Create cluster: " + clusterName : summary;
        final String bodyToStore = XdsResourceManager.injectYamlField(body, "name", clusterName);
        return xdsResourceManager.push(group, clusterName, CLUSTERS_DIRECTORY + clusterId + ".yaml",
                                       createSummary, currentAuthor(), true, bodyToStore);
    }

    /**
     * PUT /xds/groups/{group}/clusters/{cluster_id}
     *
     * <p>Updates an existing cluster.
     */
    @Put("/xds/groups/{group}/clusters/{*cluster_id}")
    @Consumes("application/yaml")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> updateCluster(
            @Param("group") String group,
            @Param("cluster_id") String clusterId,
            @Param("summary") @Nullable String summary,
            String body) {
        final String clusterName = "groups/" + group + "/clusters/" + clusterId;
        if (!CLUSTER_NAME_PATTERN.matcher(clusterName).matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid cluster name: " + clusterName));
        }
        try {
            XdsResourceManager.parseYaml(body, Cluster.newBuilder());
        } catch (IOException e) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid request body: " + e.getMessage()));
        }
        final String updateSummary = isNullOrEmpty(summary) ? "Update cluster: " + clusterName : summary;
        final String bodyToStore = XdsResourceManager.injectYamlField(body, "name", clusterName);
        return xdsResourceManager.update(group, clusterName, updateSummary, currentAuthor(), bodyToStore);
    }

    /**
     * DELETE /xds/groups/{group}/clusters/{cluster_id}
     *
     * <p>Removes a cluster.
     */
    @Delete("/xds/groups/{group}/clusters/{*cluster_id}")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> deleteCluster(
            @Param("group") String group,
            @Param("cluster_id") String clusterId,
            @Param("summary") @Nullable String summary) {
        final String clusterName = "groups/" + group + "/clusters/" + clusterId;
        if (!CLUSTER_NAME_PATTERN.matcher(clusterName).matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid cluster name: " + clusterName));
        }
        final String deleteSummary = isNullOrEmpty(summary) ? "Delete cluster: " + clusterName : summary;
        return xdsResourceManager.delete(group, clusterName, deleteSummary, currentAuthor());
    }
}
