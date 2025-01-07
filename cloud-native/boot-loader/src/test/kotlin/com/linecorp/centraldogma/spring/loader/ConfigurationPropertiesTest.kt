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

import assertk.all
import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.prop
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.server.ServerPort
import com.linecorp.centraldogma.config.GracefulShutdownTimeout
import com.linecorp.centraldogma.config.MirroringServicePluginConfig
import com.linecorp.centraldogma.config.ZooKeeperReplicationConfig
import com.linecorp.centraldogma.server.CentralDogmaConfigSpec
import com.linecorp.centraldogma.server.CustomConfigBindHandlerConfiguration
import com.linecorp.centraldogma.server.ReplicationMethod
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfigSpec
import com.linecorp.centraldogma.server.ZooKeeperServerConfigSpec
import com.linecorp.centraldogma.server.auth.AuthConfigSpec
import com.linecorp.centraldogma.server.auth.saml.Idp
import com.linecorp.centraldogma.server.auth.saml.SamlAuthProperties
import com.linecorp.centraldogma.server.auth.saml.SamlAuthProviderFactory
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfigSpec
import org.junit.jupiter.api.Test
import org.opensaml.xmlsec.signature.support.SignatureConstants
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import java.io.File
import java.util.Optional

@SpringJUnitConfig(
    TestConfiguration::class,
    initializers = [ConfigDataApplicationContextInitializer::class],
)
@TestPropertySource(properties = ["spring.config.location=classpath:full-config.yaml"])
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ConfigurationPropertiesTest(
    private val centralDogmaConfig: CentralDogmaConfigSpec,
) {
    @Test
    fun test() {
        println(centralDogmaConfig)

        assertThat(centralDogmaConfig).all {
            prop(CentralDogmaConfigSpec::dataDir).isEqualTo(File("./data"))
            prop(CentralDogmaConfigSpec::ports).isEqualTo(listOf(ServerPort(36462, SessionProtocol.HTTP)))
            prop(CentralDogmaConfigSpec::numRepositoryWorkers).isEqualTo(16)
            prop(CentralDogmaConfigSpec::repositoryCacheSpec).isEqualTo("maximumWeight=134217728,expireAfterAccess=5m")
            prop(CentralDogmaConfigSpec::gracefulShutdownTimeout)
                .isEqualTo(Optional.of(GracefulShutdownTimeout(1000L, 10000L)))

            prop("isWebAppEnabled") { it.isWebAppEnabled }.isEqualTo(true)

            prop(CentralDogmaConfigSpec::replicationConfig)
                .isInstanceOf<ZooKeeperReplicationConfig>()
                .all {
                    prop(ZooKeeperReplicationConfigSpec::method).isEqualTo(ReplicationMethod.ZOOKEEPER)
                    prop(ZooKeeperReplicationConfigSpec::secret).isEqualTo("secretKey")
                    prop(ZooKeeperReplicationConfigSpec::servers).all {
                        hasSize(1)
                        prop("1") { it[1] }.isNotNull().all {
                            prop(ZooKeeperServerConfigSpec::host).isEqualTo("127.0.0.1")
                            prop(ZooKeeperServerConfigSpec::quorumPort).isEqualTo(2030)
                            prop(ZooKeeperServerConfigSpec::electionPort).isEqualTo(2345)
                            prop(ZooKeeperServerConfigSpec::clientPort).isEqualTo(2345)
                            prop(ZooKeeperServerConfigSpec::groupId).isNull()
                            prop(ZooKeeperServerConfigSpec::weight).isEqualTo(1)
                        }
                    }
                }
            prop(CentralDogmaConfigSpec::accessLogFormat).isEqualTo("common")
            prop(CentralDogmaConfigSpec::pluginConfigs).all {
                hasSize(1)
                prop("0") { it.first() }.isInstanceOf<MirroringServicePluginConfig>().all {
                    prop(MirroringServicePluginConfigSpec::numMirroringThreads).isEqualTo(10)
                    prop(MirroringServicePluginConfigSpec::maxNumFilesPerMirror).isEqualTo(10)
                    prop(MirroringServicePluginConfigSpec::maxNumBytesPerMirror)
                        .isEqualTo(MirroringServicePluginConfigSpec.DEFAULT_MAX_NUM_BYTES_PER_MIRROR)
                }
            }
            prop(CentralDogmaConfigSpec::authConfig).isNotNull().all {
                prop(AuthConfigSpec::factoryClassName).isEqualTo(SamlAuthProviderFactory::class.qualifiedName)
                prop(AuthConfigSpec::factory).isInstanceOf<SamlAuthProviderFactory>()
                prop(AuthConfigSpec::systemAdministrators).all {
                    hasSize(1)
                    containsOnly("test@linecorp.com")
                }
                prop("properties") { it.properties(SamlAuthProperties::class.java) }.isNotNull().all {
                    prop(SamlAuthProperties::entityId).isEqualTo("https://dogma.com")
                    prop(SamlAuthProperties::hostname).isEqualTo("dogma.com")
                    prop(SamlAuthProperties::samlMetadata).isNotNull()
                    prop(SamlAuthProperties::signatureAlgorithm).isEqualTo(SignatureConstants.ALGO_ID_SIGNATURE_RSA)
                    prop(SamlAuthProperties::idp).all {
                        prop(Idp::entityId).isEqualTo("dogma-saml")
                        prop(Idp::uri).isEqualTo("https://dogma.com/signon")
                    }
                }
            }
        }

        SamlAuthProviderFactory()
            .metadataResolver(centralDogmaConfig.authConfig()!!.properties(SamlAuthProperties::class.java)!!)
    }
}

@Import(CustomConfigBindHandlerConfiguration::class)
@EnableConfigurationProperties(CentralDogmaProperties::class)
@Configuration(proxyBeanMethods = false)
class TestConfiguration
