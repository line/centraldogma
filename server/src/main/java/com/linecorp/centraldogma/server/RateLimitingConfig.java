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

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

/**
 * Per-server rate limits for HTTP API v1 content pushes and reverts.
 * The first matching repository rule is checked before the shared global limit.
 */
public final class RateLimitingConfig {

    static final RateLimitingConfig DISABLED = new RateLimitingConfig(null, ImmutableList.of());

    @Nullable
    private final RateLimitConfig global;
    private final List<RepositoryRateLimitConfig> repositories;

    /**
     * Creates rate limiting configuration with an optional global limit and ordered repository rules.
     * A missing global limit or repository rule list disables the corresponding limits.
     */
    @JsonCreator
    public RateLimitingConfig(@JsonProperty("global") @Nullable RateLimitConfig global,
                              @JsonProperty("repositories")
                              @Nullable List<RepositoryRateLimitConfig> repositories) {
        this.global = global;
        this.repositories = repositories != null ? ImmutableList.copyOf(repositories) : ImmutableList.of();
    }

    /**
     * Returns the shared global limit, or {@code null} if disabled.
     */
    @Nullable
    @JsonProperty
    public RateLimitConfig global() {
        return global;
    }

    /**
     * Returns the ordered repository rules. The first full-string match wins.
     * Each repository has an independent budget, even if it matches the same rule as another repository.
     */
    @JsonProperty
    public List<RepositoryRateLimitConfig> repositories() {
        return repositories;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("global", global)
                          .add("repositories", repositories)
                          .toString();
    }
}
