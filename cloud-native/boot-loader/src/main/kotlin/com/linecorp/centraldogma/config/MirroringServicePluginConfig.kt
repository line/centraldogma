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

package com.linecorp.centraldogma.config

import com.google.common.base.Preconditions.checkArgument
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfigSpec
import com.linecorp.centraldogma.server.plugin.AbstractPluginConfig

open class PluginConfigBase(
    enabled: Boolean,
    val type: String,
) : AbstractPluginConfig(enabled)

data class MirroringServicePluginConfig(
    private val enabled: Boolean,
    private val numMirroringThreads: Int = MirroringServicePluginConfigSpec.DEFAULT_NUM_MIRRORING_THREADS,
    private val maxNumFilesPerMirror: Int = MirroringServicePluginConfigSpec.DEFAULT_MAX_NUM_FILES_PER_MIRROR,
    private val maxNumBytesPerMirror: Long = MirroringServicePluginConfigSpec.DEFAULT_MAX_NUM_BYTES_PER_MIRROR,
) : PluginConfigBase(enabled, MirroringServicePluginConfig::class.java.name), MirroringServicePluginConfigSpec {
    init {
        checkArgument(
            numMirroringThreads > 0,
            "numMirroringThreads: %s (expected: > 0)",
            numMirroringThreads,
        )
        checkArgument(
            maxNumFilesPerMirror > 0,
            "maxNumFilesPerMirror: %s (expected: > 0)",
            maxNumFilesPerMirror,
        )
        checkArgument(
            maxNumBytesPerMirror > 0,
            "maxNumBytesPerMirror: %s (expected: > 0)",
            maxNumBytesPerMirror,
        )
    }

    override fun numMirroringThreads(): Int = numMirroringThreads

    override fun maxNumFilesPerMirror(): Int = maxNumFilesPerMirror

    override fun maxNumBytesPerMirror(): Long = maxNumBytesPerMirror
}
