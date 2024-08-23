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
package com.linecorp.centraldogma.xds.internal;

import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.plugin.AllReplicasPlugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginInitContext;

/**
 * A plugin that creates an internal group before the control plane plugin starts.
 * This is for checking the deadlock in the plugin initialization.
 * See <a href="https://github.com/line/centraldogma/pull/1024#issuecomment-2306377086">this comment</a>.
 */
public final class CreatingInternalGroupPlugin extends AllReplicasPlugin {

    @Override
    public void init(PluginInitContext pluginInitContext) {
        pluginInitContext.internalProjectInitializer().initialize(XDS_CENTRAL_DOGMA_PROJECT);
        pluginInitContext.commandExecutor()
                         .execute(Command.forcePush(Command.createRepository(
                                 Author.SYSTEM, XDS_CENTRAL_DOGMA_PROJECT, "my-group")))
                         .join();
    }

    @Override
    public CompletionStage<Void> start(PluginContext context) {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> stop(PluginContext context) {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public Class<?> configType() {
        return getClass();
    }
}
