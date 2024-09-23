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

import com.google.common.base.Ascii
import com.linecorp.centraldogma.internal.Jackson
import com.linecorp.centraldogma.server.ReplicationConfig
import com.linecorp.centraldogma.server.ReplicationMethod
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfigSpec
import com.linecorp.centraldogma.server.ZooKeeperServerConfigSpec
import com.linecorp.centraldogma.server.auth.AuthConfigSpec
import com.linecorp.centraldogma.server.auth.AuthProviderFactory
import org.springframework.validation.annotation.Validated
import java.util.function.Function

open class ReplicationConfigImpl(
    private val method: ReplicationMethod,
) : ReplicationConfig {
    override fun method(): ReplicationMethod = method
}

data class NoneReplicationConfig(private val method: ReplicationMethod) : ReplicationConfigImpl(method)

@Validated
data class ZooKeeperServerConfig(
    private val host: String,
    private val quorumPort: Int,
    private val electionPort: Int,
    private val clientPort: Int,
    private val groupId: Int?,
    private val weight: Int = 1,
) : ZooKeeperServerConfigSpec {
    init {
        check(weight >= 0) { "weight: $weight (expected: >= 0)" }
    }

    override fun host(): String = host

    override fun quorumPort(): Int = ZooKeeperServerConfigSpec.validatePort(quorumPort, "quorumPort")

    override fun electionPort(): Int = ZooKeeperServerConfigSpec.validatePort(electionPort, "electionPort")

    override fun clientPort(): Int = clientPort

    override fun groupId(): Int? = groupId

    override fun weight(): Int = weight
}

data class ZooKeeperReplicationConfig(
    private val method: ReplicationMethod,
    private val servers: Map<Int, ZooKeeperServerConfig>,
    private val secret: String,
    private val additionalProperties: Map<String, String> = emptyMap(),
    private val timeoutMillis: Int = ZooKeeperReplicationConfigSpec.DEFAULT_TIMEOUT_MILLIS,
    private val numWorkers: Int = ZooKeeperReplicationConfigSpec.DEFAULT_NUM_WORKERS,
    private val maxLogCount: Int = ZooKeeperReplicationConfigSpec.DEFAULT_MAX_LOG_COUNT,
    private val minLogAgeMillis: Long = ZooKeeperReplicationConfigSpec.DEFAULT_MIN_LOG_AGE_MILLIS,
) : ReplicationConfigImpl(method), ZooKeeperReplicationConfigSpec {
    private val serverId: Int = ZooKeeperReplicationConfigSpec.findServerId(servers)

    override fun serverId(): Int = serverId

    override fun serverConfig(): ZooKeeperServerConfigSpec = servers[serverId]!!

    override fun servers(): Map<Int, ZooKeeperServerConfigSpec> = servers

    override fun secret(): String = secret

    override fun additionalProperties(): Map<String, String> = additionalProperties

    override fun timeoutMillis(): Int = timeoutMillis

    override fun numWorkers(): Int = numWorkers

    override fun maxLogCount(): Int = maxLogCount

    override fun minLogAgeMillis(): Long = minLogAgeMillis
}

data class AuthConfig(
    private val factoryClassName: String,
    private val administrators: Set<String> = emptySet(),
    private val caseSensitiveLoginNames: Boolean = true,
    private val sessionCacheSpec: String = AuthConfigSpec.DEFAULT_SESSION_CACHE_SPEC,
    private val sessionTimeoutMillis: Long = AuthConfigSpec.DEFAULT_SESSION_TIMEOUT_MILLIS,
    private val sessionValidationSchedule: String = AuthConfigSpec.DEFAULT_SESSION_VALIDATION_SCHEDULE,
    private val properties: Map<String, Any>?,
) : AuthConfigSpec {
    private val factory =
        AuthConfigSpec::class.java.classLoader
            .loadClass(factoryClassName)
            .getDeclaredConstructor()
            .newInstance() as AuthProviderFactory

    override fun factory(): AuthProviderFactory = factory

    override fun factoryClassName(): String = factoryClassName

    override fun systemAdministrators(): Set<String> = administrators

    override fun caseSensitiveLoginNames(): Boolean = caseSensitiveLoginNames

    override fun sessionCacheSpec(): String = sessionCacheSpec

    override fun sessionTimeoutMillis(): Long = sessionTimeoutMillis

    override fun sessionValidationSchedule(): String = sessionValidationSchedule

    override fun <T : Any> properties(clazz: Class<T>): T? =
        properties?.let {
            Jackson.convertValue(it, clazz)
        }

    override fun loginNameNormalizer(): Function<String, String> =
        if (caseSensitiveLoginNames) {
            Function.identity()
        } else {
            Function(Ascii::toLowerCase)
        }
}
