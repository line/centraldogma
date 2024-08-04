/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.centraldogma.it;

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.server.plugin.AllReplicasPlugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginInitContext;

public final class TestAllReplicasPlugin extends AllReplicasPlugin {

    @Override
    public void init(PluginInitContext pluginInitContext) {
        pluginInitContext.serverBuilder()
                         .service("/hello", (ctx, req) -> HttpResponse.of("Hello, world!"));
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
        // Return the plugin class itself because it does not have a configuration.
        return TestAllReplicasPlugin.class;
    }
}
