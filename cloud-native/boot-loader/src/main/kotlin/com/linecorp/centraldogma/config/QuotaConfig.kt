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
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.centraldogma.server.CorsConfigSpec
import com.linecorp.centraldogma.server.ManagementConfigSpec
import com.linecorp.centraldogma.server.QuotaConfigSpec

data class QuotaConfig(
    private val requestQuota: Int,
    private val timeWindowSeconds: Int,
) : QuotaConfigSpec {
    override fun timeWindowSeconds(): Int = timeWindowSeconds

    override fun requestQuota(): Int = requestQuota
}

data class CorsConfig(
    private val allowedOrigins: List<String>,
    private val maxAgeSecond: Int,
) : CorsConfigSpec {
    override fun allowedOrigins(): List<String> = allowedOrigins

    override fun maxAgeSeconds(): Int = maxAgeSecond
}

data class ManagementConfig(
    private val protocol: String = ManagementConfigSpec.DEFAULT_PROTOCOL,
    private val address: String?,
    private val port: Int,
    private val path: String = ManagementConfigSpec.DEFAULT_PATH,
) : ManagementConfigSpec {
    private val sessionProtocol =
        SessionProtocol.of(protocol).also {
            checkArgument(
                it != SessionProtocol.PROXY,
                "protocol: %s (expected: one of %s)",
                protocol,
                SessionProtocol.httpAndHttpsValues(),
            )
        }

    init {
        checkArgument(port in 0..65535, "management.port: %s (expected: 0-65535)", port)
    }

    override fun protocol(): SessionProtocol = sessionProtocol

    override fun address(): String? = address

    override fun port(): Int = port

    override fun path(): String = path
}
