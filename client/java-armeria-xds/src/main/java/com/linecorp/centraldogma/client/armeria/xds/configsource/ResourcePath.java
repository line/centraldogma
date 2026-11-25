/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.centraldogma.client.armeria.xds.configsource;

import static com.google.common.base.Preconditions.checkArgument;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.QueryParams;
import com.linecorp.centraldogma.common.Query;

/**
 * Parses a resource name encoded as {@code {project}/{repo}/{path}} into its
 * constituent parts. The first two segments are the Central Dogma project and
 * repository name, and the remainder (prefixed with {@code /}) is the file path.
 *
 * <p>An optional {@code ?profile=} query parameter can be appended to specify
 * a per-resource variable file (profile) for template rendering.
 *
 * <p>For example, {@code "myproject/myrepo/clusters/my-cluster.json.ftl?profile=/vars/x.yaml"}
 * is parsed as:
 * <ul>
 *   <li>project: {@code "myproject"}</li>
 *   <li>repo: {@code "myrepo"}</li>
 *   <li>path: {@code "/clusters/my-cluster.json.ftl"}</li>
 *   <li>profile: {@code "/vars/x.yaml"}</li>
 *   <li>ftl: {@code true}</li>
 * </ul>
 */
final class ResourcePath {

    private final String project;
    private final String repo;
    private final String path;
    @Nullable
    private final String profile;
    private final boolean ftl;

    static ResourcePath parse(String resourceName) {
        // Split off query string if present.
        String name = resourceName;
        String profile = null;
        final int questionMark = resourceName.indexOf('?');
        if (questionMark >= 0) {
            final String queryString = resourceName.substring(questionMark + 1);
            name = resourceName.substring(0, questionMark);
            final QueryParams params = QueryParams.fromQueryString(queryString);
            profile = params.get("profile");
            checkArgument(profile == null || !profile.isEmpty(),
                          "Invalid resource name (empty profile): %s", resourceName);
        }

        final int firstSlash = name.indexOf('/');
        checkArgument(firstSlash > 0, "Invalid resource name (missing project): %s", resourceName);
        final int secondSlash = name.indexOf('/', firstSlash + 1);
        checkArgument(secondSlash > firstSlash + 1,
                      "Invalid resource name (missing repo): %s", resourceName);
        checkArgument(secondSlash < name.length() - 1,
                      "Invalid resource name (missing path): %s", resourceName);

        final String project = name.substring(0, firstSlash);
        final String repo = name.substring(firstSlash + 1, secondSlash);
        final String filePath = '/' + name.substring(secondSlash + 1);

        final boolean ftl = filePath.endsWith(".ftl");

        return new ResourcePath(project, repo, filePath, profile, ftl);
    }

    private ResourcePath(String project, String repo, String path,
                         @Nullable String profile, boolean ftl) {
        this.project = project;
        this.repo = repo;
        this.path = path;
        this.profile = profile;
        this.ftl = ftl;
    }

    String project() {
        return project;
    }

    String repo() {
        return repo;
    }

    /**
     * Returns the file path within the repository (e.g., {@code "/clusters/my-cluster.json.ftl"}).
     */
    String path() {
        return path;
    }

    /**
     * Returns the profile path if specified via {@code ?profile=}, or {@code null}.
     */
    @Nullable
    String profile() {
        return profile;
    }

    /**
     * Returns {@code true} if the file path ends with {@code .ftl}, indicating template rendering is needed.
     */
    boolean isFtl() {
        return ftl;
    }

    /**
     * Returns the path without the {@code .ftl} suffix, so Jackson dispatches to the correct parser
     * (e.g., {@code .json.ftl} → {@code .json}, {@code .yaml.ftl} → {@code .yaml}).
     *
     * @throws IllegalStateException if this is not an ftl resource
     */
    String basePath() {
        if (!ftl) {
            throw new IllegalStateException("Not an ftl resource: " + path);
        }
        return path.substring(0, path.length() - 4);
    }

    /**
     * Returns the appropriate {@link Query} based on the file extension.
     * Only used for non-ftl files. For ftl files, {@link Query#ofText(String)} is used instead.
     * <ul>
     *   <li>{@code .json} → {@link Query#ofJson(String)}</li>
     *   <li>{@code .yaml}, {@code .yml} → {@link Query#ofYaml(String)}</li>
     * </ul>
     */
    Query<JsonNode> query() {
        if (ftl) {
            throw new IllegalStateException("Use Query.ofText() for ftl resources: " + path);
        }
        if (path.endsWith(".yaml") || path.endsWith(".yml")) {
            return Query.ofYaml(path);
        }
        return Query.ofJson(path);
    }
}
