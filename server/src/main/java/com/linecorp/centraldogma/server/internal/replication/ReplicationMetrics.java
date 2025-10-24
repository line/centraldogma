/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.centraldogma.server.internal.replication;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.metric.MoreMeters;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

final class ReplicationMetrics {

    private final String projectName;
    private final Timer lockAcquireSuccessTimer;
    private final Timer lockAcquireFailureTimer;
    private final Timer commandExecutionTimer;
    private final Timer logReplayTimer;
    private final Timer logStoreTimer;

    ReplicationMetrics(MeterRegistry registry, String projectName) {
        this.projectName = projectName;
        lockAcquireSuccessTimer = MoreMeters.newTimer(registry, "replication.lock.waiting",
                                                      ImmutableList.of(Tag.of("project", projectName),
                                                                       Tag.of("acquired", "true")));
        lockAcquireFailureTimer = MoreMeters.newTimer(registry, "replication.lock.waiting",
                                                      ImmutableList.of(Tag.of("project", projectName),
                                                                       Tag.of("acquired", "false")));
        commandExecutionTimer = MoreMeters.newTimer(registry, "replication.command.execution",
                                                    ImmutableList.of(Tag.of("project", projectName)));
        logReplayTimer = MoreMeters.newTimer(registry, "replication.log.replay",
                                            ImmutableList.of(Tag.of("project", projectName)));
        logStoreTimer = MoreMeters.newTimer(registry, "replication.log.store",
                                            ImmutableList.of(Tag.of("project", projectName)));
    }

    Timer lockAcquireSuccessTimer() {
        return lockAcquireSuccessTimer;
    }

    Timer lockAcquireFailureTimer() {
        return lockAcquireFailureTimer;
    }

    Timer commandExecutionTimer() {
        return commandExecutionTimer;
    }

    Timer logReplayTimer() {
        return logReplayTimer;
    }

    Timer logStoreTimer() {
        return logStoreTimer;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ReplicationMetrics)) {
            return false;
        }
        final ReplicationMetrics that = (ReplicationMetrics) o;
        return projectName.equals(that.projectName);
    }

    @Override
    public int hashCode() {
        return projectName.hashCode();
    }
}
