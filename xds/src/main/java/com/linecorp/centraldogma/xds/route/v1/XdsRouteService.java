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
package com.linecorp.centraldogma.xds.route.v1;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.currentAuthor;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.ROUTES_DIRECTORY;
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

import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

/**
 * Annotated service object for managing routes.
 */
public final class XdsRouteService {

    private static final Pattern ROUTE_NAME_PATTERN =
            Pattern.compile("^groups/([^/]+)/routes/" + LEGACY_RESOURCE_ID_PATTERN_STRING + '$');

    private final XdsResourceManager xdsResourceManager;

    /**
     * Creates a new instance.
     */
    public XdsRouteService(XdsResourceManager xdsResourceManager) {
        this.xdsResourceManager = xdsResourceManager;
    }

    /**
     * POST /xds/groups/{group}/routes
     *
     * <p>Creates a new route.
     */
    @Post("/xds/groups/{group}/routes")
    @Consumes("application/yaml")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> createRoute(
            @Param("group") String group,
            @Param("route_id") String routeId,
            @Param("summary") @Nullable String summary,
            String body) {
        if (!RESOURCE_ID_PATTERN.matcher(routeId).matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid route ID: " + routeId));
        }
        final String routeName = "groups/" + group + ROUTES_DIRECTORY + routeId;
        try {
            XdsResourceManager.parseYaml(body, RouteConfiguration.newBuilder());
        } catch (IOException e) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid request body: " + e.getMessage()));
        }
        final String createSummary = isNullOrEmpty(summary) ? "Create route: " + routeName : summary;
        final String bodyToStore = XdsResourceManager.injectYamlField(body, "name", routeName);
        return xdsResourceManager.push(group, routeName, ROUTES_DIRECTORY + routeId + ".yaml",
                                       createSummary, currentAuthor(), true, bodyToStore);
    }

    /**
     * PUT /xds/groups/{group}/routes/{route_id}
     *
     * <p>Updates an existing route.
     */
    @Put("/xds/groups/{group}/routes/{*route_id}")
    @Consumes("application/yaml")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> updateRoute(
            @Param("group") String group,
            @Param("route_id") String routeId,
            @Param("summary") @Nullable String summary,
            String body) {
        final String routeName = "groups/" + group + "/routes/" + routeId;
        if (!ROUTE_NAME_PATTERN.matcher(routeName).matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid route name: " + routeName));
        }
        try {
            XdsResourceManager.parseYaml(body, RouteConfiguration.newBuilder());
        } catch (IOException e) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid request body: " + e.getMessage()));
        }
        final String updateSummary = isNullOrEmpty(summary) ? "Update route: " + routeName : summary;
        final String bodyToStore = XdsResourceManager.injectYamlField(body, "name", routeName);
        return xdsResourceManager.update(group, routeName, updateSummary, currentAuthor(), bodyToStore);
    }

    /**
     * DELETE /xds/groups/{group}/routes/{route_id}
     *
     * <p>Removes a route.
     */
    @Delete("/xds/groups/{group}/routes/{*route_id}")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> deleteRoute(
            @Param("group") String group,
            @Param("route_id") String routeId,
            @Param("summary") @Nullable String summary) {
        final String routeName = "groups/" + group + "/routes/" + routeId;
        if (!ROUTE_NAME_PATTERN.matcher(routeName).matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid route name: " + routeName));
        }
        final String deleteSummary = isNullOrEmpty(summary) ? "Delete route: " + routeName : summary;
        return xdsResourceManager.delete(group, routeName, deleteSummary, currentAuthor());
    }
}
