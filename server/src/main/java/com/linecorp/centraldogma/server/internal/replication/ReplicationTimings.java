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

import org.jspecify.annotations.Nullable;

interface ReplicationTimings {

    static ReplicationTimings of(@Nullable ReplicationMetrics metrics) {
        if (metrics == null) {
            return NoopReplicationTimings.INSTANCE;
        }
        return new DefaultReplicationTimings(metrics);
    }

    void startExecutorSubmit();

    void startExecutorExecution();

    void startLockAcquisition(long startNanos);

    void endLockAcquisition(boolean lockAcquired);

    void startLockRelease();

    void endLockRelease();

    void startCommandExecution();

    void endCommandExecution();

    void startLogReplay();

    void endLogReplay();

    void startLogStore();

    void endLogStore();

    void record();

    String toText();
}
