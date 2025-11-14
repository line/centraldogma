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

import com.linecorp.armeria.common.util.TextFormatter;

final class DefaultReplicationTimings implements ReplicationTimings {

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
    private boolean commandExecutionEnded;

    private long logReplayStartNanos;
    private long logReplayDurationNanos;
    private boolean logReplayEnded;

    private long logStoreStartNanos;
    private long logStoreDurationNanos;
    private boolean logStoreEnded;
    private long logStoreEndNanos;

    DefaultReplicationTimings(ReplicationMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void startExecutorSubmit() {
        executorSubmitStartNanos = System.nanoTime();
    }

    @Override
    public void startExecutorExecution() {
        executorQueueLatencyNanos = System.nanoTime() - executorSubmitStartNanos;
    }

    @Override
    public void startLockAcquisition(long startNanos) {
        lockAcquisitionStartNanos = startNanos;
    }

    @Override
    public void endLockAcquisition(boolean lockAcquired) {
        lockAcquisitionDurationNanos = System.nanoTime() - lockAcquisitionStartNanos;
        this.lockAcquired = lockAcquired;
    }

    @Override
    public void startLockRelease() {
        lockReleaseStartNanos = System.nanoTime();
    }

    @Override
    public void endLockRelease() {
        lockReleaseDurationNanos = System.nanoTime() - lockReleaseStartNanos;
    }

    @Override
    public void startCommandExecution() {
        commandExecutionStartNanos = System.nanoTime();
    }

    @Override
    public void endCommandExecution() {
        commandExecutionDurationNanos = System.nanoTime() - commandExecutionStartNanos;
        commandExecutionEnded = true;
    }

    @Override
    public void startLogReplay() {
        logReplayStartNanos = System.nanoTime();
    }

    @Override
    public void endLogReplay() {
        logReplayDurationNanos = System.nanoTime() - logReplayStartNanos;
        logReplayEnded = true;
    }

    @Override
    public void startLogStore() {
        logStoreStartNanos = System.nanoTime();
    }

    @Override
    public void endLogStore() {
        logStoreEndNanos = System.nanoTime();
        logStoreDurationNanos = logStoreEndNanos - logStoreStartNanos;
        logStoreEnded = true;
    }

    @Override
    public void record() {
        metrics.executorQueueLatencyTimer().record(executorQueueLatencyNanos, TimeUnit.NANOSECONDS);
        if (lockAcquired) {
            metrics.lockAcquireSuccessTimer().record(lockAcquisitionDurationNanos, TimeUnit.NANOSECONDS);
        } else {
            metrics.lockAcquireFailureTimer().record(lockAcquisitionDurationNanos, TimeUnit.NANOSECONDS);
        }
        metrics.lockReleaseTimer().record(lockReleaseDurationNanos, TimeUnit.NANOSECONDS);
        if (commandExecutionEnded) {
            metrics.commandExecutionTimer().record(commandExecutionDurationNanos, TimeUnit.NANOSECONDS);
        }
        if (logReplayEnded) {
            metrics.logReplayTimer().record(logReplayDurationNanos, TimeUnit.NANOSECONDS);
        }
        if (logStoreEnded) {
            metrics.logStoreTimer().record(logStoreDurationNanos, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public String toText() {
        if (!logStoreEnded) {
            return "{ not completed yet }";
        }

        final StringBuilder sb = new StringBuilder();
        sb.append("{total=");
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

