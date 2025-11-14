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

enum NoopReplicationTimings implements ReplicationTimings {
    INSTANCE;

    @Override
    public void startExecutorSubmit() {}

    @Override
    public void startExecutorExecution() {}

    @Override
    public void startLockAcquisition(long startNanos) {}

    @Override
    public void endLockAcquisition(boolean lockAcquired) {}

    @Override
    public void startLockRelease() {}

    @Override
    public void endLockRelease() {}

    @Override
    public void startCommandExecution() {}

    @Override
    public void endCommandExecution() {}

    @Override
    public void startLogReplay() {}

    @Override
    public void endLogReplay() {}

    @Override
    public void startLogStore() {}

    @Override
    public void endLogStore() {}

    @Override
    public void record() {}

    @Override
    public String toText() {
        return "{ no timings recorded }";
    }
}
