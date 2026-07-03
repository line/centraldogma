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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
class LogMeta {

    private final int replicaId;
    private final long timestamp;
    private final int size;
    @Nullable
    private final String commandType;
    @Nullable
    private final String projectName;
    @Nullable
    private final String repoName;
    @Nullable
    private final Boolean compressed;
    @Nullable
    private final Boolean encrypted;
    private final List<Long> blocks;

    @JsonCreator
    LogMeta(@JsonProperty(value = "replicaId", required = true) int replicaId,
            @JsonProperty(value = "timestamp", defaultValue = "0") @Nullable Long timestamp,
            @JsonProperty("size") int size,
            @JsonProperty("commandType") @Nullable String commandType,
            @JsonProperty("projectName") @Nullable String projectName,
            @JsonProperty("repoName") @Nullable String repoName,
            @JsonProperty("blocks") List<Long> blocks,
            @JsonProperty("compressed") @Nullable Boolean compressed,
            @JsonProperty("encrypted") @Nullable Boolean encrypted) {

        this.replicaId = replicaId;
        if (timestamp == null) {
            timestamp = 0L;
        }
        this.timestamp = timestamp;
        this.size = size;
        this.commandType = commandType;
        this.projectName = projectName;
        this.repoName = repoName;
        this.compressed = compressed;
        this.encrypted = encrypted;
        this.blocks = blocks;
    }

    LogMeta(int replicaId, Long timestamp, int size,
            @Nullable String commandType, @Nullable String projectName, @Nullable String repoName,
            @Nullable Boolean compressed, @Nullable Boolean encrypted) {
        this(replicaId, timestamp, size, commandType, projectName, repoName,
             new ArrayList<>(4), compressed, encrypted);
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

    @Nullable
    @JsonProperty("commandType")
    String commandType() {
        return commandType;
    }

    @Nullable
    @JsonProperty("projectName")
    String projectName() {
        return projectName;
    }

    @Nullable
    @JsonProperty("repoName")
    String repoName() {
        return repoName;
    }

    @Nullable
    @JsonProperty("compressed")
    Boolean compressed() {
        return compressed;
    }

    @Nullable
    @JsonProperty("encrypted")
    Boolean encrypted() {
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
               Objects.equals(commandType, logMeta.commandType) &&
               Objects.equals(projectName, logMeta.projectName) &&
               Objects.equals(repoName, logMeta.repoName) &&
               Objects.equals(compressed, logMeta.compressed) &&
               Objects.equals(encrypted, logMeta.encrypted) &&
               Objects.equals(blocks, logMeta.blocks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(replicaId, timestamp, size, commandType, projectName, repoName,
                            compressed, encrypted, blocks);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("replicaId", replicaId)
                          .add("timestamp", timestamp)
                          .add("size", size)
                          .add("commandType", commandType)
                          .add("projectName", projectName)
                          .add("repoName", repoName)
                          .add("compressed", compressed)
                          .add("encrypted", encrypted)
                          .add("blocks", blocks)
                          .toString();
    }
}
