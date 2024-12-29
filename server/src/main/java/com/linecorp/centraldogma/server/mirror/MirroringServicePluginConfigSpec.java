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

import com.linecorp.centraldogma.server.plugin.PluginConfig;

/**
 * A mirroring service plugin configuration spec.
 */
public interface MirroringServicePluginConfigSpec extends PluginConfig {
    int DEFAULT_NUM_MIRRORING_THREADS = 16;
    int DEFAULT_MAX_NUM_FILES_PER_MIRROR = 8192;
    long DEFAULT_MAX_NUM_BYTES_PER_MIRROR = 32 * 1048576; // 32 MiB

    /**
     * Returns the number of mirroring threads.
     */
    int numMirroringThreads();

    /**
     * Returns the maximum allowed number of files per mirror.
     */
    int maxNumFilesPerMirror();

    /**
     * Returns the maximum allowed number of bytes per mirror.
     */
    long maxNumBytesPerMirror();
}
