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

package com.linecorp.centraldogma.server

import com.linecorp.centraldogma.config.MirroringServicePluginConfig
import com.linecorp.centraldogma.config.NoneReplicationConfig
import com.linecorp.centraldogma.config.PluginConfigBase
import com.linecorp.centraldogma.config.ReplicationConfigImpl
import com.linecorp.centraldogma.config.ZooKeeperReplicationConfig
import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor
import org.springframework.boot.context.properties.bind.AbstractBindHandler
import org.springframework.boot.context.properties.bind.BindContext
import org.springframework.boot.context.properties.bind.BindHandler
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import kotlin.reflect.KClass
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.jvm.jvmName

abstract class PolymorphicBindHandler<BASE_TYPE : Any>(
    parent: BindHandler,
    private val kClass: KClass<BASE_TYPE>,
) : AbstractBindHandler(parent) {
    override fun onSuccess(
        name: ConfigurationPropertyName?,
        target: Bindable<*>?,
        context: BindContext,
        result: Any?,
    ): Any {
        if (result == null || !kClass.isSuperclassOf(result::class)) {
            return super.onSuccess(name, target, context, result)
        }

        @Suppress("UNCHECKED_CAST")
        val bindType = decideBindType(result as BASE_TYPE)
        return context.binder.bind(name, Bindable.of(bindType.java)).get()
    }

    abstract fun decideBindType(result: BASE_TYPE): KClass<out BASE_TYPE>
}

class ReplicationMethodBindHandlerAdvisor : ConfigurationPropertiesBindHandlerAdvisor {
    override fun apply(bindHandler: BindHandler): BindHandler {
        return object : PolymorphicBindHandler<ReplicationConfigImpl>(
            bindHandler,
            ReplicationConfigImpl::class,
        ) {
            override fun decideBindType(result: ReplicationConfigImpl): KClass<out ReplicationConfigImpl> =
                when (result.method()) {
                    ReplicationMethod.NONE -> NoneReplicationConfig::class
                    ReplicationMethod.ZOOKEEPER -> ZooKeeperReplicationConfig::class
                }
        }
    }
}

class PluginConfigBindHandlerAdvisor : ConfigurationPropertiesBindHandlerAdvisor {
    override fun apply(bindHandler: BindHandler): BindHandler {
        return object : PolymorphicBindHandler<PluginConfigBase>(
            bindHandler,
            PluginConfigBase::class,
        ) {
            private val knownPluginClasses =
                listOf(MirroringServicePluginConfig::class)
                    .associateBy { it.jvmName }

            override fun decideBindType(result: PluginConfigBase): KClass<out PluginConfigBase> {
                return knownPluginClasses[result.type] ?: error("unknown plugin type ${result.type}")
            }
        }
    }
}

@Import(
    ReplicationMethodBindHandlerAdvisor::class,
    PluginConfigBindHandlerAdvisor::class,
)
@Configuration
class CustomConfigBindHandlerConfiguration
