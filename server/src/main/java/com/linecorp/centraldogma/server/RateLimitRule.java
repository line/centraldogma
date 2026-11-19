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

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * A token bucket's capacity and refill rate, expressed as a request count per time unit.
 * Buckets start full and refill gradually, up to their capacity.
 * A count of {@code -1} allows unlimited requests, and {@code 0} rejects all requests.
 * Global limits omit the pattern; repository rules require a pattern matched against the entire
 * {@code projectName/repoName} and give each repository its own token bucket.
 */
public final class RateLimitRule {

    @Nullable
    private final Pattern pattern;
    private final long requests;
    private final TimeUnit timeUnit;

    /**
     * Creates a global limit with capacity {@code requests}, replenishing that many tokens
     * per {@code timeUnit}.
     * A count of {@code -1} allows unlimited requests, and {@code 0} rejects all requests.
     * The count must be at least {@code -1}, and both properties are required.
     */
    public RateLimitRule(long requests, TimeUnit timeUnit) {
        this(null, requests, timeUnit);
    }

    /**
     * Creates a limit with an optional repository pattern, bucket capacity and refill time unit.
     * The pattern must be {@code null} for a global limit and nonempty for a repository rule.
     * A count of {@code -1} allows unlimited requests, and {@code 0} rejects all requests.
     * The count must be at least {@code -1}, and both {@code requests} and {@code timeUnit} are required.
     */
    @JsonCreator
    public RateLimitRule(@JsonProperty("pattern") @Nullable String pattern,
                         @JsonProperty(value = "requests", required = true) long requests,
                         @JsonProperty(value = "timeUnit", required = true) TimeUnit timeUnit) {
        checkArgument(pattern == null || !pattern.isEmpty(), "pattern must not be empty");
        this.pattern = pattern != null ? Pattern.compile(pattern) : null;
        checkArgument(requests >= -1, "requests: %s (expected: >= -1)", requests);
        this.requests = requests;
        this.timeUnit = requireNonNull(timeUnit, "timeUnit");
    }

    /**
     * Returns the regular expression matched against {@code projectName/repoName},
     * or {@code null} for a global limit.
     */
    @Nullable
    @JsonProperty
    @JsonInclude(Include.NON_NULL)
    public String pattern() {
        return pattern != null ? pattern.pattern() : null;
    }

    /**
     * Returns the bucket capacity and the number of tokens replenished per time unit,
     * or {@code -1} for unlimited requests and {@code 0} to reject all requests.
     */
    @JsonProperty
    public long requests() {
        return requests;
    }

    /**
     * Returns the refill time unit. {@link TimeUnit#DAYS} means exactly 24 hours.
     */
    @JsonProperty
    public TimeUnit timeUnit() {
        return timeUnit;
    }

    /**
     * Returns whether the entire {@code projectName/repoName} matches this rule,
     * or {@code true} if no pattern is specified.
     */
    public boolean matches(String projectAndRepoName) {
        return pattern == null || pattern.matcher(projectAndRepoName).matches();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("pattern", pattern())
                          .add("requests", requests)
                          .add("timeUnit", timeUnit)
                          .toString();
    }
}
