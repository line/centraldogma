/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.server.mirror;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.plugin.AbstractPluginConfig;

/**
 * A mirroring service plugin configuration.
 */
public final class MirroringServicePluginConfig extends AbstractPluginConfig {

    public static final MirroringServicePluginConfig INSTANCE =
            new MirroringServicePluginConfig(true, null, null, null);

    static final int DEFAULT_NUM_MIRRORING_THREADS = 16;
    static final int DEFAULT_MAX_NUM_FILES_PER_MIRROR = 8192;
    static final long DEFAULT_MAX_NUM_BYTES_PER_MIRROR = 32 * 1048576; // 32 MiB

    private final int numMirroringThreads;
    private final int maxNumFilesPerMirror;
    private final long maxNumBytesPerMirror;

    /**
     * Creates a new instance.
     */
    public MirroringServicePluginConfig(boolean enabled) {
        this(enabled, null, null, null);
    }

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public MirroringServicePluginConfig(
            @JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("numMirroringThreads") @Nullable Integer numMirroringThreads,
            @JsonProperty("maxNumFilesPerMirror") @Nullable Integer maxNumFilesPerMirror,
            @JsonProperty("maxNumBytesPerMirror") @Nullable Long maxNumBytesPerMirror) {
        super(enabled);
        this.numMirroringThreads = firstNonNull(numMirroringThreads, DEFAULT_NUM_MIRRORING_THREADS);
        checkArgument(this.numMirroringThreads > 0,
                      "numMirroringThreads: %s (expected: > 0)", this.numMirroringThreads);
        this.maxNumFilesPerMirror = firstNonNull(maxNumFilesPerMirror, DEFAULT_MAX_NUM_FILES_PER_MIRROR);
        checkArgument(this.maxNumFilesPerMirror > 0,
                      "maxNumFilesPerMirror: %s (expected: > 0)", this.maxNumFilesPerMirror);
        this.maxNumBytesPerMirror = firstNonNull(maxNumBytesPerMirror, DEFAULT_MAX_NUM_BYTES_PER_MIRROR);
        checkArgument(this.maxNumBytesPerMirror > 0,
                      "maxNumBytesPerMirror: %s (expected: > 0)", this.maxNumBytesPerMirror);
    }

    /**
     * Returns the number of mirroring threads.
     */
    @JsonProperty
    public int numMirroringThreads() {
        return numMirroringThreads;
    }

    /**
     * Returns the maximum allowed number of files per mirror.
     */
    @JsonProperty
    public int maxNumFilesPerMirror() {
        return maxNumFilesPerMirror;
    }

    /**
     * Returns the maximum allowed number of bytes per mirror.
     */
    @JsonProperty
    public long maxNumBytesPerMirror() {
        return maxNumBytesPerMirror;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("numMirroringThreads", numMirroringThreads)
                          .add("maxNumFilesPerMirror", maxNumFilesPerMirror)
                          .add("maxNumBytesPerMirror", maxNumBytesPerMirror)
                          .toString();
    }
}
