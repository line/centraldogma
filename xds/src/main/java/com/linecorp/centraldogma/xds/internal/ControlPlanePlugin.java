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

package com.linecorp.centraldogma.xds.internal;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.server.plugin.AllReplicasPlugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginInitContext;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;

public final class ControlPlanePlugin extends AllReplicasPlugin {

    public static final String XDS_CENTRAL_DOGMA_PROJECT = "@xds";

    @Nullable
    private volatile ControlPlaneService controlPlaneService;

    @Override
    public void init(PluginInitContext pluginInitContext) {
        final InternalProjectInitializer projectInitializer = pluginInitContext.internalProjectInitializer();
        projectInitializer.initialize(XDS_CENTRAL_DOGMA_PROJECT);
        final ControlPlaneService controlPlaneService = new ControlPlaneService(
                pluginInitContext.projectManager().get(XDS_CENTRAL_DOGMA_PROJECT),
                pluginInitContext.meterRegistry());
        this.controlPlaneService = controlPlaneService;
        try {
            controlPlaneService.start(pluginInitContext).get(60, TimeUnit.SECONDS);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to start control plane plugin in 60 seconds.", t);
        }
    }

    @Override
    public CompletionStage<Void> start(PluginContext context) {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> stop(PluginContext context) {
        final ControlPlaneService controlPlaneService = this.controlPlaneService;
        if (controlPlaneService != null) {
            controlPlaneService.stop();
        }
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public Class<?> configType() {
        return ControlPlanePluginConfig.class;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("configType", configType())
                          .add("target", target())
                          .toString();
    }
}
