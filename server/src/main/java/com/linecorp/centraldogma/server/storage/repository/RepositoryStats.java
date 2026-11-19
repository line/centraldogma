/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.centraldogma.server.storage.repository;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An immutable snapshot of repository statistics, including an approximate rolling commit count.
 * Counts are bounded at one more than {@link #recommendedCount()} to detect when the recommendation
 * is exceeded.
 * The window is treated as a contiguous revision range, so clock skew and out-of-order commit timestamps
 * can affect the count.
 */
public final class RepositoryStats {

    private final boolean enabled;
    private final int windowHours;
    private final int commitCount;
    private final int recommendedCount;
    private final Instant updatedAt;

    /**
     * Returns statistics with tracking disabled.
     */
    public static RepositoryStats disabled() {
        return new RepositoryStats(false, 0, 0, 0, Instant.now());
    }

    /**
     * Creates a snapshot evaluated at the specified UTC instant.
     */
    public RepositoryStats(boolean enabled, int windowHours, int commitCount, int recommendedCount,
                           Instant updatedAt) {
        if (enabled) {
            checkArgument(windowHours > 0, "windowHours: %s (expected: > 0)", windowHours);
            checkArgument(commitCount >= 0, "commitCount: %s (expected: >= 0)", commitCount);
            checkArgument(recommendedCount > 0, "recommendedCount: %s (expected: > 0)", recommendedCount);
        } else {
            checkArgument(windowHours == 0, "windowHours: %s (expected: 0 when disabled)", windowHours);
            checkArgument(commitCount == 0, "commitCount: %s (expected: 0 when disabled)", commitCount);
            checkArgument(recommendedCount == 0, "recommendedCount: %s (expected: 0 when disabled)",
                          recommendedCount);
        }
        this.enabled = enabled;
        this.windowHours = windowHours;
        this.commitCount = commitCount;
        this.recommendedCount = recommendedCount;
        this.updatedAt = requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * Returns whether repository statistics are enabled.
     */
    @JsonProperty
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the rolling window in hours.
     */
    @JsonProperty
    public int windowHours() {
        return windowHours;
    }

    /**
     * Returns the approximate number of commits in the window, bounded at {@link #recommendedCount()} plus one.
     * A count above the recommendation is a lower bound on the actual count.
     */
    @JsonProperty
    public int commitCount() {
        return commitCount;
    }

    /**
     * Returns the recommended number of commits in the window.
     */
    @JsonProperty
    public int recommendedCount() {
        return recommendedCount;
    }

    /**
     * Returns the snapshot's evaluation time.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @JsonProperty
    public Instant updatedAt() {
        return updatedAt;
    }
}
