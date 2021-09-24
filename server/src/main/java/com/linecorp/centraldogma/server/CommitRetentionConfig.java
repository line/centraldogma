/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.centraldogma.server;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;

import javax.annotation.Nullable;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * A configuration for retaining commits in a {@link Repository}.
 */
public final class CommitRetentionConfig {

    // The minimum of minRetentionCommits
    private static final int MINIMUM_MINIMUM_RETENTION_COMMITS = 5000;

    private static final String DEFAULT_SCHEDULE = "0 0 * * * ?"; // Every day
    private static final CronParser cronParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(
            CronType.QUARTZ));

    private final int minRetentionCommits;
    private final int minRetentionDays;
    private final Cron schedule;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public CommitRetentionConfig(@JsonProperty("minRetentionCommits") int minRetentionCommits,
                                 @JsonProperty("minRetentionDays") int minRetentionDays,
                                 @JsonProperty("schedule") @Nullable String schedule) {
        checkArgument(minRetentionCommits == 0 || minRetentionCommits >= MINIMUM_MINIMUM_RETENTION_COMMITS,
                      "minRetentionCommits: %s (expected: 0 || >= %s)",
                      minRetentionCommits, MINIMUM_MINIMUM_RETENTION_COMMITS);
        checkArgument(minRetentionDays >= 0,
                      "minRetentionDays: %s (expected: >= 0)", minRetentionDays);
        this.minRetentionCommits = minRetentionCommits;
        this.minRetentionDays = minRetentionDays;
        this.schedule = cronParser.parse(firstNonNull(schedule, DEFAULT_SCHEDULE));
    }

    /**
     * Returns the minimum number of commits that a {@link Repository} should retain. 0 means that
     * the number of commits are not taken into account when {@link Repository#removeOldCommits(int, int)}
     * is called.
     */
    public int minRetentionCommits() {
        return minRetentionCommits;
    }

    /**
     * Returns the minimum number of days of a commit that a {@link Repository} should retain. 0 means that
     * the number of retention days of commits are not taken into account when
     * {@link Repository#removeOldCommits(int, int)} is called.
     */
    public int minRetentionDays() {
        return minRetentionDays;
    }

    /**
     * Returns the schedule of the job that removes old commits.
     */
    public Cron schedule() {
        return schedule;
    }

    @Override
    public String toString() {
        return toStringHelper(this).add("minRetentionCommits", minRetentionCommits)
                                   .add("minRetentionDays", minRetentionDays)
                                   .add("schedule", schedule)
                                   .toString();
    }
}
