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

import com.linecorp.centraldogma.server.CentralDogma
import com.linecorp.centraldogma.server.CentralDogma.TokenAuthorizerCreator
import com.linecorp.centraldogma.server.CentralDogmaConfigSpec
import com.linecorp.centraldogma.server.CustomConfigBindHandlerConfiguration
import com.linecorp.centraldogma.server.auth.AuthProviderParameters
import com.linecorp.centraldogma.server.auth.saml.SessionGroupTokenAuthorizer
import com.linecorp.centraldogma.server.command.Command
import com.linecorp.centraldogma.server.internal.api.auth.ApplicationTokenAuthorizer
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

class CentralDogmaManager(val centralDogma: CentralDogma) : DisposableBean, InitializingBean {
    override fun afterPropertiesSet() {
        centralDogma.start().join()
    }

    override fun destroy() {
        centralDogma.stop().join()
    }
}

@ConfigurationProperties("central-dogma-admin")
data class CentralDogmaAdminProperties(
    val adminGroupNames: Set<String> = emptySet(),
)

@Import(CustomConfigBindHandlerConfiguration::class)
@EnableConfigurationProperties(
    CentralDogmaProperties::class,
    CentralDogmaAdminProperties::class,
)
@SpringBootApplication
class CentralDogmaSpringLoader {
    @Bean
    fun centralDogma(
        properties: CentralDogmaProperties,
        adminProperties: CentralDogmaAdminProperties,
        meterRegistry: MeterRegistry,
    ): CentralDogmaManager {
        val authConfig = properties.authConfig()

        Thread.sleep(10000)
        val tokenAuthorizerCreator =
            TokenAuthorizerCreator { mds, sessionManager ->
                ApplicationTokenAuthorizer(mds::findTokenBySecret)
                    .orElse(SessionGroupTokenAuthorizer(sessionManager, adminProperties.adminGroupNames))
            }
        val authProviderCreator =
            if (authConfig == null) {
                null
            } else {
                CentralDogma.AuthProviderCreator { commandExecutor, sessionManager, mds ->
                    checkNotNull(sessionManager) { "SessionManager is null" }

                    val parameters =
                        AuthProviderParameters(
                            tokenAuthorizerCreator.create(mds, sessionManager),
                            properties,
                            sessionManager::generateSessionId,
                            { commandExecutor.execute(Command.createSession(it)) },
                            { commandExecutor.execute(Command.removeSession(it)) },
                        )

                    authConfig.factory().create(parameters)
                }
            }

        val centralDogmaConfig =
            object : CentralDogmaConfigSpec by properties {
                override fun toString(): String = "Contains sensitive values. Please use actuator instead."
            }

        return CentralDogmaManager(
            CentralDogma.forConfig(centralDogmaConfig, meterRegistry)
                .authProviderCreator(authProviderCreator)
                .tokenAuthorizerCreator(tokenAuthorizerCreator),
        )
    }
}

fun main(args: Array<String>) {
    runApplication<CentralDogmaSpringLoader>(*args)
}
