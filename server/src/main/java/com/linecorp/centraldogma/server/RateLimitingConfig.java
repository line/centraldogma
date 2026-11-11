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

import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * A per-server rate limit for content pushes and reverts matched against {@code projectName/repoName}.
 * Rules are evaluated in order, and the first full-string match wins.
 * Each repository has an independent budget, even when it matches the same rule as another repository.
 */
public final class RateLimitingConfig {

    private final Pattern pattern;
    private final double requestsPerSecond;

    /**
     * Creates a rule with a regular expression and a rate. A rate of {@code 0} means unlimited requests.
     * Invalid regular expressions and rates are rejected when the configuration is created.
     */
    @JsonCreator
    public RateLimitingConfig(
            @JsonProperty(value = "pattern", required = true) String pattern,
            @JsonProperty(value = "requestsPerSecond", required = true) Double requestsPerSecond) {
        requireNonNull(pattern, "pattern");
        checkArgument(!pattern.isEmpty(), "pattern must not be empty");
        this.pattern = Pattern.compile(pattern);
        requireNonNull(requestsPerSecond, "requestsPerSecond");
        checkArgument(Double.isFinite(requestsPerSecond) && requestsPerSecond >= 0,
                      "requestsPerSecond: %s (expected: a finite value >= 0)", requestsPerSecond);
        this.requestsPerSecond = requestsPerSecond;
    }

    /**
     * Returns the regular expression matched against {@code projectName/repoName}.
     */
    @JsonProperty
    public String pattern() {
        return pattern.pattern();
    }

    /**
     * Returns the content pushes and reverts per second for each matching repository,
     * or {@code 0} for unlimited requests.
     */
    @JsonProperty
    public double requestsPerSecond() {
        return requestsPerSecond;
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
                          .add("requestsPerSecond", requestsPerSecond)
                          .toString();
    }
}
