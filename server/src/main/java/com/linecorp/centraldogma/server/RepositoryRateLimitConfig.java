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
import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * A rate limit matched against the entire {@code projectName/repoName}.
 * Each matching repository receives its own token bucket.
 */
public final class RepositoryRateLimitConfig {

    private final Pattern pattern;
    private final RateLimitConfig limit;

    /**
     * Creates a repository rule with a regular expression, bucket capacity and refill time unit.
     * A count of {@code -1} disables the repository limit, while the global limit still applies.
     * A count of {@code 0} rejects all requests to matching repositories.
     */
    @JsonCreator
    public RepositoryRateLimitConfig(
            @JsonProperty(value = "pattern", required = true) String pattern,
            @JsonProperty(value = "requests", required = true) long requests,
            @JsonProperty(value = "timeUnit", required = true) TimeUnit timeUnit) {
        requireNonNull(pattern, "pattern");
        checkArgument(!pattern.isEmpty(), "pattern must not be empty");
        this.pattern = Pattern.compile(pattern);
        limit = new RateLimitConfig(requests, timeUnit);
    }

    /**
     * Returns the regular expression matched against {@code projectName/repoName}.
     */
    @JsonProperty
    public String pattern() {
        return pattern.pattern();
    }

    /**
     * Returns the bucket capacity and tokens replenished per time unit,
     * or {@code -1} for unlimited requests and {@code 0} to reject all requests.
     */
    @JsonProperty
    public long requests() {
        return limit.requests();
    }

    /**
     * Returns the refill time unit.
     */
    @JsonProperty
    public TimeUnit timeUnit() {
        return limit.timeUnit();
    }

    /**
     * Returns whether the entire {@code projectName/repoName} matches this rule.
     */
    public boolean matches(String projectAndRepoName) {
        return pattern.matcher(projectAndRepoName).matches();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("pattern", pattern())
                          .add("requests", requests())
                          .add("timeUnit", timeUnit())
                          .toString();
    }
}
