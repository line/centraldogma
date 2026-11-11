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
package com.linecorp.centraldogma.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.centraldogma.internal.Util.validateProjectName;
import static com.linecorp.centraldogma.internal.Util.validateRepositoryName;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

/**
 * Per-server rate limits for content push requests. Each repository has an independent budget.
 */
public final class RateLimitingConfig {

    private final double defaultRepositoryRequestsPerSecond;
    private final Map<String, ProjectConfig> projects;

    /**
     * Creates a configuration with a default rate and optional project and repository overrides.
     * A rate of {@code 0} disables rate limiting. If the default rate is unspecified, it is {@code 0}.
     */
    @JsonCreator
    public RateLimitingConfig(
            @JsonProperty("defaultRepositoryRequestsPerSecond")
            @Nullable Double defaultRepositoryRequestsPerSecond,
            @JsonProperty("projects") @Nullable Map<String, ProjectConfig> projects) {
        this.defaultRepositoryRequestsPerSecond =
                validateRate(defaultRepositoryRequestsPerSecond != null ?
                             defaultRepositoryRequestsPerSecond : 0);
        this.projects = projects != null ? ImmutableMap.copyOf(projects) : ImmutableMap.of();
        this.projects.keySet().forEach(name -> validateProjectName(name, "projects", true));
    }

    /**
     * Returns the default content pushes per second for each repository, or {@code 0} for unlimited pushes.
     */
    @JsonProperty
    public double defaultRepositoryRequestsPerSecond() {
        return defaultRepositoryRequestsPerSecond;
    }

    /**
     * Returns the project-specific defaults and repository overrides.
     */
    @JsonProperty
    public Map<String, ProjectConfig> projects() {
        return projects;
    }

    /**
     * Returns the repository override, project default, or server default, in that order.
     * A returned value of {@code 0} means unlimited requests.
     */
    public double requestsPerSecond(String projectName, String repoName) {
        final ProjectConfig project = projects.get(projectName);
        if (project != null) {
            final Double repoRate = project.repos().get(repoName);
            if (repoRate != null) {
                return repoRate;
            }
            final Double projectRate = project.defaultRepositoryRequestsPerSecond();
            if (projectRate != null) {
                return projectRate;
            }
        }
        return defaultRepositoryRequestsPerSecond;
    }

    private static double validateRate(double rate) {
        checkArgument(Double.isFinite(rate) && rate >= 0,
                      "requestsPerSecond: %s (expected: a finite value >= 0)", rate);
        return rate;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("defaultRepositoryRequestsPerSecond", defaultRepositoryRequestsPerSecond)
                          .add("projects", projects)
                          .toString();
    }

    /**
     * A default rate for each repository in a project and overrides for individual repositories.
     */
    public static final class ProjectConfig {

        @Nullable
        private final Double defaultRepositoryRequestsPerSecond;
        private final Map<String, Double> repos;

        /**
         * Creates a configuration with an optional project default and repository overrides.
         * An unspecified default inherits the server default. A rate of {@code 0} disables rate limiting.
         */
        @JsonCreator
        public ProjectConfig(
                @JsonProperty("defaultRepositoryRequestsPerSecond")
                @Nullable Double defaultRepositoryRequestsPerSecond,
                @JsonProperty("repos") @Nullable Map<String, Double> repos) {
            this.defaultRepositoryRequestsPerSecond = defaultRepositoryRequestsPerSecond != null ?
                                                     validateRate(defaultRepositoryRequestsPerSecond) : null;
            this.repos = repos != null ? ImmutableMap.copyOf(repos) : ImmutableMap.of();
            this.repos.forEach((name, rate) -> {
                validateRepositoryName(name, "repos");
                validateRate(rate);
            });
        }

        /**
         * Returns the default rate for each repository in this project, or {@code null} to inherit it.
         */
        @Nullable
        @JsonProperty
        public Double defaultRepositoryRequestsPerSecond() {
            return defaultRepositoryRequestsPerSecond;
        }

        /**
         * Returns the repository-specific rates. A rate of {@code 0} means unlimited requests.
         */
        @JsonProperty
        public Map<String, Double> repos() {
            return repos;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("defaultRepositoryRequestsPerSecond", defaultRepositoryRequestsPerSecond)
                              .add("repos", repos)
                              .toString();
        }
    }
}
