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

package com.linecorp.centraldogma.spring.loader

import com.google.common.base.Preconditions.checkArgument
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.server.ClientAddressSource
import com.linecorp.centraldogma.config.AuthConfig
import com.linecorp.centraldogma.config.CorsConfig
import com.linecorp.centraldogma.config.GracefulShutdownTimeout
import com.linecorp.centraldogma.config.ManagementConfig
import com.linecorp.centraldogma.config.PluginConfigBase
import com.linecorp.centraldogma.config.QuotaConfig
import com.linecorp.centraldogma.config.ReplicationConfigImpl
import com.linecorp.centraldogma.config.ServerPort
import com.linecorp.centraldogma.config.TlsConfig
import com.linecorp.centraldogma.server.AbstractCentralDogmaConfig
import com.linecorp.centraldogma.server.CorsConfigSpec
import com.linecorp.centraldogma.server.GracefulShutdownTimeoutSpec
import com.linecorp.centraldogma.server.ManagementConfigSpec
import com.linecorp.centraldogma.server.QuotaConfigSpec
import com.linecorp.centraldogma.server.ReplicationConfig
import com.linecorp.centraldogma.server.TlsConfigSpec
import com.linecorp.centraldogma.server.ZoneConfigSpec
import com.linecorp.centraldogma.server.auth.AuthConfigSpec
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache.validateCacheSpec
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Optional
import java.util.function.Predicate

@ConfigurationProperties("central-dogma")
data class CentralDogmaProperties(
    private val dataDir: File,
    @NestedConfigurationProperty
    private val ports: List<ServerPort>,
    @NestedConfigurationProperty
    private val tls: TlsConfig? = null,
    private val trustedProxyAddresses: List<String>? = null,
    private val clientAddressSources: List<String>? = null,
    private val numWorkers: Int? = null,
    private val maxNumConnections: Int? = null,
    private val requestTimeoutMillis: Long? = null,
    private val idleTimeoutMillis: Long? = null,
    private val maxFrameLength: Int? = null,
    /**
     * default value from [com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_NUM_REPOSITORY_WORKERS]
     */
    private val numRepositoryWorkers: Int = 16,
    private val repositoryCacheSpec: String = "maximumWeight=134217728,expireAfterAccess=5m",
    private val maxRemovedRepositoryAgeMillis: Long = 604_800_000,
    @NestedConfigurationProperty
    private val gracefulShutdownTimeout: GracefulShutdownTimeout? = null,
    private val webAppEnabled: Boolean = true,
    private val webAppTitle: String? = null,
    @NestedConfigurationProperty
    private val replication: ReplicationConfigImpl,
    private val csrfTokenRequiredForThrift: Boolean = true,
    private val accessLogFormat: String? = null,
    @NestedConfigurationProperty
    private val authentication: AuthConfig? = null,
    @NestedConfigurationProperty
    private val writeQuotaPerRepository: QuotaConfig? = null,
    @NestedConfigurationProperty
    private val cors: CorsConfig? = null,
    private val pluginConfigs: List<PluginConfigBase>? = null,
    @NestedConfigurationProperty
    private val management: ManagementConfig? = null,
) : AbstractCentralDogmaConfig(pluginConfigs) {
    private val trustedProxyAddressPredicate: Predicate<InetAddress>

    private val clientAddressSourceList: List<ClientAddressSource>

    private val armeriaServerPorts: List<com.linecorp.armeria.server.ServerPort> =
        ports.map { serverPort ->
            val localAddress =
                if (serverPort.localAddress.host == "*") {
                    InetSocketAddress(serverPort.localAddress.port)
                } else {
                    InetSocketAddress(serverPort.localAddress.host, serverPort.localAddress.port)
                }

            val protocols =
                serverPort.protocols.map {
                    SessionProtocol.of(it)
                }.toSet()

            com.linecorp.armeria.server.ServerPort(localAddress, protocols)
        }

    init {
        checkArgument(
            this.numRepositoryWorkers > 0,
            "numRepositoryWorkers: %s (expected: > 0)",
            this.numRepositoryWorkers,
        )
        checkArgument(
            this.maxRemovedRepositoryAgeMillis >= 0,
            "maxRemovedRepositoryAgeMillis: %s (expected: >= 0)",
            this.maxRemovedRepositoryAgeMillis,
        )

        validateCacheSpec(repositoryCacheSpec)

        val hasTrustedProxyAddrCfg = !trustedProxyAddresses.isNullOrEmpty()

        trustedProxyAddressPredicate =
            if (hasTrustedProxyAddrCfg) {
                toTrustedProxyAddressPredicate(trustedProxyAddresses!!)
            } else {
                Predicate<InetAddress> { false }
            }

        clientAddressSourceList =
            toClientAddressSourceList(
                clientAddressSources, hasTrustedProxyAddrCfg,
                armeriaServerPorts.any(com.linecorp.armeria.server.ServerPort::hasProxyProtocol),
            )
    }

    override fun dataDir(): File = dataDir

    override fun ports(): List<com.linecorp.armeria.server.ServerPort> = armeriaServerPorts

    override fun tls(): TlsConfigSpec? = tls

    override fun trustedProxyAddresses(): List<String>? = trustedProxyAddresses

    override fun clientAddressSources(): List<String>? = clientAddressSources

    override fun numWorkers(): Optional<Int> = Optional.ofNullable(numWorkers)

    override fun maxNumConnections(): Optional<Int> = Optional.ofNullable(maxNumConnections)

    override fun requestTimeoutMillis(): Optional<Long> = Optional.ofNullable(requestTimeoutMillis)

    override fun idleTimeoutMillis(): Optional<Long> = Optional.ofNullable(idleTimeoutMillis)

    override fun maxFrameLength(): Optional<Int> = Optional.ofNullable(maxFrameLength)

    override fun numRepositoryWorkers(): Int = numRepositoryWorkers

    override fun maxRemovedRepositoryAgeMillis(): Long = maxRemovedRepositoryAgeMillis

    override fun repositoryCacheSpec(): String = repositoryCacheSpec

    override fun gracefulShutdownTimeout(): Optional<GracefulShutdownTimeoutSpec> = Optional.ofNullable(gracefulShutdownTimeout)

    override fun isWebAppEnabled(): Boolean = webAppEnabled

    override fun webAppTitle(): String? = webAppTitle

    override fun replicationConfig(): ReplicationConfig = replication

    override fun isCsrfTokenRequiredForThrift(): Boolean = this.csrfTokenRequiredForThrift

    override fun accessLogFormat(): String? = accessLogFormat

    override fun authConfig(): AuthConfigSpec? = authentication

    override fun writeQuotaPerRepository(): QuotaConfigSpec? = writeQuotaPerRepository

    override fun corsConfig(): CorsConfigSpec? = cors

    override fun managementConfig(): ManagementConfigSpec? = management

    // TODO 이따 구현
    override fun zone(): ZoneConfigSpec? = null

    override fun trustedProxyAddressPredicate(): Predicate<InetAddress> = trustedProxyAddressPredicate

    override fun clientAddressSourceList(): List<ClientAddressSource> = clientAddressSourceList
}
