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
package com.linecorp.centraldogma.xds.listener.v1;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.currentAuthor;
import static com.linecorp.centraldogma.xds.internal.ControlPlaneService.LISTENERS_DIRECTORY;
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

import io.envoyproxy.envoy.config.listener.v3.Listener;

/**
 * Annotated service object for managing listeners.
 */
public final class XdsListenerService {

    private static final Pattern LISTENER_NAME_PATTERN =
            Pattern.compile("^groups/([^/]+)/listeners/" + LEGACY_RESOURCE_ID_PATTERN_STRING + '$');

    private final XdsResourceManager xdsResourceManager;

    /**
     * Creates a new instance.
     */
    public XdsListenerService(XdsResourceManager xdsResourceManager) {
        this.xdsResourceManager = xdsResourceManager;
    }

    /**
     * POST /xds/groups/{group}/listeners
     *
     * <p>Creates a new listener.
     */
    @Post("/xds/groups/{group}/listeners")
    @Consumes("application/yaml")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> createListener(
            @Param("group") String group,
            @Param("listener_id") String listenerId,
            @Param("summary") @Nullable String summary,
            String body) {
        if (!RESOURCE_ID_PATTERN.matcher(listenerId).matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid listener ID: " + listenerId));
        }
        final String listenerName = listenerName(group, listenerId);
        try {
            XdsResourceManager.parseYaml(body, Listener.newBuilder());
        } catch (IOException e) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid request body: " + e.getMessage()));
        }
        final String createSummary = isNullOrEmpty(summary) ? "Create listener: " + listenerName : summary;
        final String bodyToStore = XdsResourceManager.injectYamlField(body, "name", listenerName);
        return xdsResourceManager.push(group, listenerName,
                                       LISTENERS_DIRECTORY + listenerId + ".yaml",
                                       createSummary, currentAuthor(), true, bodyToStore);
    }

    /**
     * PUT /xds/groups/{group}/listeners/{listener_id}
     *
     * <p>Updates an existing listener.
     */
    @Put("/xds/groups/{group}/listeners/{*listener_id}")
    @Consumes("application/yaml")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> updateListener(
            @Param("group") String group,
            @Param("listener_id") String listenerId,
            @Param("summary") @Nullable String summary,
            String body) {
        final String listenerName = listenerName(group, listenerId);
        if (!LISTENER_NAME_PATTERN.matcher(listenerName).matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid listener name: " + listenerName));
        }
        try {
            XdsResourceManager.parseYaml(body, Listener.newBuilder());
        } catch (IOException e) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid request body: " + e.getMessage()));
        }
        final String updateSummary = isNullOrEmpty(summary) ? "Update listener: " + listenerName : summary;
        final String bodyToStore = XdsResourceManager.injectYamlField(body, "name", listenerName);
        return xdsResourceManager.update(group, listenerName, updateSummary, currentAuthor(), bodyToStore);
    }

    /**
     * DELETE /xds/groups/{group}/listeners/{listener_id}
     *
     * <p>Removes a listener.
     */
    @Delete("/xds/groups/{group}/listeners/{*listener_id}")
    @RequiresXdsGroupRole(RepositoryRole.WRITE)
    public CompletableFuture<HttpResponse> deleteListener(
            @Param("group") String group,
            @Param("listener_id") String listenerId,
            @Param("summary") @Nullable String summary) {
        final String listenerName = listenerName(group, listenerId);
        if (!LISTENER_NAME_PATTERN.matcher(listenerName).matches()) {
            return CompletableFuture.completedFuture(
                    XdsResourceManager.errorResponse(HttpStatus.BAD_REQUEST,
                                                     "Invalid listener name: " + listenerName));
        }
        final String deleteSummary = isNullOrEmpty(summary) ? "Delete listener: " + listenerName : summary;
        return xdsResourceManager.delete(group, listenerName, deleteSummary, currentAuthor());
    }

    private static String listenerName(String group, String listenerId) {
        return "groups/" + group + LISTENERS_DIRECTORY + listenerId;
    }
}
