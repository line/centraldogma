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
import static org.apache.curator.shaded.com.google.common.base.Preconditions.checkArgument;

import javax.annotation.Nullable;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * A configuration for {@link Repository} garbage collection.
 */
public final class RepositoryGarbageCollectionConfig {

    private static final String DEFAULT_SCHEDULE = "0 0 * * * ?"; // Every day
    private static final CronParser cronParser =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    private final int minNumNewCommits;
    private final Cron schedule;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public RepositoryGarbageCollectionConfig(@JsonProperty("minNumNewCommits") int minNumNewCommits,
                                             @JsonProperty("schedule") @Nullable String schedule) {
        checkArgument(minNumNewCommits > 0, "minNumNewCommits: %s (expected: > 0)", minNumNewCommits);

        this.minNumNewCommits = minNumNewCommits;
        this.schedule = cronParser.parse(firstNonNull(schedule, DEFAULT_SCHEDULE));
    }

    /**
     * Returns the minimum required number of commits newly added to run a garbage collection.
     */
    public int minNumNewCommits() {
        return minNumNewCommits;
    }

    /**
     * Returns the schedule when garbage collections is suppose to be triggered.
     */
    public Cron schedule() {
        return schedule;
    }
}
