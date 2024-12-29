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
import com.linecorp.centraldogma.server.GracefulShutdownTimeoutSpec

data class GracefulShutdownTimeout(
    private val quietPeriodMillis: Long,
    private val timeoutMillis: Long,
) : GracefulShutdownTimeoutSpec {
    init {
        checkArgument(
            quietPeriodMillis >= 0,
            "quietPeriodMillis: %s (expected: >= 0)",
            quietPeriodMillis,
        )
        checkArgument(
            timeoutMillis >= 0,
            "timeoutMillis: %s (expected: >= 0)",
            timeoutMillis,
        )
    }

    override fun quietPeriodMillis(): Long = quietPeriodMillis

    override fun timeoutMillis(): Long = timeoutMillis
}
