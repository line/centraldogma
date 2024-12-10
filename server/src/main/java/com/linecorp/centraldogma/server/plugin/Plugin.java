/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.centraldogma.server.plugin;

import java.util.concurrent.CompletionStage;

import com.linecorp.centraldogma.server.CentralDogmaConfig;

/**
 * An interface which defines callbacks for a plug-in. If you want to initialize a {@link Plugin} by configuring
 * the Central Dogma server (e.g. adding a service for your plugin), use {@link AllReplicasPlugin}.
 */
public interface Plugin {
    /**
     * Returns the {@link PluginTarget} which specifies the replicas that this {@link Plugin} is applied to.
     */
    PluginTarget target(CentralDogmaConfig config);

    /**
     * Invoked when this {@link Plugin} is supposed to be started.
     *
     * @param context the context which consists of the objects required to execute this {@link Plugin}
     */
    CompletionStage<Void> start(PluginContext context);

    /**
     * Invoked when this {@link Plugin} is supposed to be stopped.
     *
     * @param context the context which consists of the objects required to execute this {@link Plugin}
     */
    CompletionStage<Void> stop(PluginContext context);

    /**
     * Returns {@code true} if this {@link Plugin} is enabled.
     */
    default boolean isEnabled(CentralDogmaConfig config) {
        final PluginConfig pluginConfig = config.pluginConfigMap().get(configType());
        if (pluginConfig == null) {
            // Enabled if not found.
            return true;
        }
        return pluginConfig.enabled();
    }

    /**
     * Returns the type of the configuration for this {@link Plugin}.
     */
    Class<?> configType();
}
