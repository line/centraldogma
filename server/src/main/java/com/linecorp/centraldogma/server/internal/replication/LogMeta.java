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

import static com.google.common.base.MoreObjects.firstNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

@JsonIgnoreProperties(ignoreUnknown = true)
class LogMeta {

    private final int replicaId;
    private final long timestamp;
    private final int size;
    private final boolean compressed;
    private final boolean encrypted;
    private final List<Long> blocks;

    @JsonCreator
    LogMeta(@JsonProperty(value = "replicaId", required = true) int replicaId,
            @JsonProperty(value = "timestamp", defaultValue = "0") Long timestamp,
            @JsonProperty("size") int size,
            @JsonProperty("blocks") List<Long> blocks,
            @Nullable @JsonProperty("compressed") Boolean compressed,
            @Nullable @JsonProperty("encrypted") Boolean encrypted) {

        this.replicaId = replicaId;
        if (timestamp == null) {
            timestamp = 0L;
        }
        this.timestamp = timestamp;
        this.size = size;
        // Defaults to false for backward compatibility.
        this.compressed = firstNonNull(compressed, false);
        this.encrypted = firstNonNull(encrypted, false);
        this.blocks = blocks;
    }

    LogMeta(int replicaId, Long timestamp, int size, Boolean compressed, Boolean encrypted) {
        this(replicaId, timestamp, size, new ArrayList<>(4), compressed, encrypted);
    }

    @JsonProperty
    int replicaId() {
        return replicaId;
    }

    @JsonProperty
    long timestamp() {
        return timestamp;
    }

    @JsonProperty
    int size() {
        return size;
    }

    @JsonProperty("compressed")
    boolean compressed() {
        return compressed;
    }

    @JsonProperty("encrypted")
    boolean encrypted() {
        return encrypted;
    }

    @JsonProperty
    List<Long> blocks() {
        return Collections.unmodifiableList(blocks);
    }

    public void appendBlock(long blockId) {
        blocks.add(blockId);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LogMeta)) {
            return false;
        }
        final LogMeta logMeta = (LogMeta) o;
        return replicaId == logMeta.replicaId &&
               timestamp == logMeta.timestamp &&
               size == logMeta.size &&
               compressed == logMeta.compressed &&
               encrypted == logMeta.encrypted &&
               Objects.equals(blocks, logMeta.blocks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(replicaId, timestamp, size, compressed, encrypted, blocks);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("replicaId", replicaId)
                          .add("timestamp", timestamp)
                          .add("size", size)
                          .add("compressed", compressed)
                          .add("encrypted", encrypted)
                          .add("blocks", blocks)
                          .toString();
    }
}
