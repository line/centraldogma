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

package com.linecorp.centraldogma.server.internal.replication;

import java.util.Arrays;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.google.common.base.MoreObjects;

/**
 * A context object for {@link ReplicationLog} which is used to
 * capture the state of the log when it is being replayed.
 */
final class ReplicationLogContext {

    private long replayRevision = -1;
    @Nullable
    private LogMeta meta;
    @Nullable
    private ReplicationLog<?> log;
    private byte @Nullable [] bytes;

    void setReplayRevision(long replayRevision) {
        this.replayRevision = replayRevision;
    }

    @Nullable
    LogMeta meta() {
        return meta;
    }

    void setMeta(LogMeta meta) {
        this.meta = meta;
    }

    @Nullable
    ReplicationLog<?> log() {
        return log;
    }

    void setLog(ReplicationLog<?> log) {
        this.log = log;
    }

    void setBytes(byte[] bytes) {
        this.bytes = bytes;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ReplicationLogContext)) {
            return false;
        }
        final ReplicationLogContext that = (ReplicationLogContext) o;

        return replayRevision == that.replayRevision &&
               Objects.equals(meta, that.meta) &&
               Objects.equals(log, that.log) &&
               Objects.deepEquals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(replayRevision, meta, log);
        result = 31 * result + Arrays.hashCode(bytes);
        return result;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("replayRevision", replayRevision)
                          .add("meta", meta)
                          .add("log", log)
                          // A recovery payload can be tens of megabytes; never dump it into a log line.
                          .add("bytes", bytes == null ? null : bytes.length + " bytes")
                          .toString();
    }
}
