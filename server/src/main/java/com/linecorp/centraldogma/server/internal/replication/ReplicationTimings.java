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

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.util.TextFormatter;

final class ReplicationTimings {

    @Nullable
    private final ReplicationMetrics metrics;

    private long executorSubmitStartNanos;
    private long executorQueueLatencyNanos;
    private long lockAcquisitionStartNanos;
    private long lockAcquisitionDurationNanos;
    private boolean lockAcquired;
    private long lockReleaseStartNanos;
    private long lockReleaseDurationNanos;
    private long commandExecutionStartNanos;
    private long commandExecutionDurationNanos;
    private long logReplayStartNanos;
    private long logReplayDurationNanos;
    private long logStoreStartNanos;
    private long logStoreDurationNanos;
    private long logStoreEndNanos;

    ReplicationTimings(@Nullable ReplicationMetrics metrics) {
        this.metrics = metrics;
    }

    void startExecutorSubmit() {
        executorSubmitStartNanos = System.nanoTime();
    }

    void startExecutorExecution() {
        executorQueueLatencyNanos = System.nanoTime() - executorSubmitStartNanos;
    }

    void startLockAcquisition(long startNanos) {
        lockAcquisitionStartNanos = startNanos;
    }

    void endLockAcquisition(boolean lockAcquired) {
        lockAcquisitionDurationNanos = System.nanoTime() - lockAcquisitionStartNanos;
        this.lockAcquired = lockAcquired;
    }

    void startLockRelease() {
        lockReleaseStartNanos = System.nanoTime();
    }

    void endLockRelease() {
        lockReleaseDurationNanos = System.nanoTime() - lockReleaseStartNanos;
    }

    void startCommandExecution() {
        commandExecutionStartNanos = System.nanoTime();
    }

    void endCommandExecution() {
        commandExecutionDurationNanos = System.nanoTime() - commandExecutionStartNanos;
    }

    void startLogReplay() {
        logReplayStartNanos = System.nanoTime();
    }

    void endLogReplay() {
        logReplayDurationNanos = System.nanoTime() - logReplayStartNanos;
    }

    void startLogStore() {
        logStoreStartNanos = System.nanoTime();
    }

    void endLogStore() {
        logStoreEndNanos = System.nanoTime();
        logStoreDurationNanos = logStoreEndNanos - logStoreStartNanos;
    }

    void record() {
        if (metrics == null) {
            return;
        }

        metrics.executorQueueLatencyTimer().record(executorQueueLatencyNanos, TimeUnit.NANOSECONDS);
        if (lockAcquired) {
            metrics.lockAcquireSuccessTimer().record(lockAcquisitionDurationNanos, TimeUnit.NANOSECONDS);
        } else {
            metrics.lockAcquireFailureTimer().record(lockAcquisitionDurationNanos, TimeUnit.NANOSECONDS);
        }
        metrics.lockReleaseTimer().record(lockReleaseDurationNanos, TimeUnit.NANOSECONDS);
        metrics.commandExecutionTimer().record(commandExecutionDurationNanos, TimeUnit.NANOSECONDS);
        metrics.logReplayTimer().record(logReplayDurationNanos, TimeUnit.NANOSECONDS);
        metrics.logStoreTimer().record(logStoreDurationNanos, TimeUnit.NANOSECONDS);
    }

    String timingsString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("{ total=");
        TextFormatter.appendElapsed(sb, logStoreEndNanos - executorSubmitStartNanos);
        sb.append(", executorQueueLatency=");
        TextFormatter.appendElapsed(sb, executorQueueLatencyNanos);
        sb.append(", lockAcquisition=");
        TextFormatter.appendElapsed(sb, lockAcquisitionDurationNanos);
        sb.append(", lockRelease=");
        TextFormatter.appendElapsed(sb, lockReleaseDurationNanos);
        sb.append(", commandExecution=");
        TextFormatter.appendElapsed(sb, commandExecutionDurationNanos);
        sb.append(", logReplay=");
        TextFormatter.appendElapsed(sb, logReplayDurationNanos);
        sb.append(", logStore=");
        TextFormatter.appendElapsed(sb, logStoreDurationNanos);
        sb.append('}');
        return sb.toString();
    }
}

