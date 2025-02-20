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
package com.linecorp.centraldogma.server.internal.storage;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;

public final class PurgeSchedulingServicePlugin implements Plugin {

    @Nullable
    private volatile PurgeSchedulingService purgeSchedulingService;

    @Override
    public PluginTarget target(CentralDogmaConfig config) {
        return PluginTarget.LEADER_ONLY;
    }

    @Override
    public synchronized CompletionStage<Void> start(PluginContext context) {
        requireNonNull(context, "context");

        PurgeSchedulingService purgeSchedulingService = this.purgeSchedulingService;
        if (purgeSchedulingService == null) {
            final CentralDogmaConfig cfg = context.config();
            purgeSchedulingService = new PurgeSchedulingService(context.projectManager(),
                                                                context.purgeWorker(),
                                                                cfg.maxRemovedRepositoryAgeMillis());
            this.purgeSchedulingService = purgeSchedulingService;
        }
        final MetadataService metadataService = new MetadataService(context.projectManager(),
                                                                    context.commandExecutor(),
                                                                    context.internalProjectInitializer());
        purgeSchedulingService.start(context.commandExecutor(), metadataService);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> stop(PluginContext context) {
        final PurgeSchedulingService purgeSchedulingService = this.purgeSchedulingService;
        if (purgeSchedulingService != null && purgeSchedulingService.isStarted()) {
            purgeSchedulingService.stop();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isEnabled(CentralDogmaConfig config) {
        return requireNonNull(config, "config").maxRemovedRepositoryAgeMillis() > 0;
    }

    @Override
    public Class<?> configType() {
        // Return the plugin class itself because it does not have a configuration.
        return getClass();
    }

    @Nullable
    public PurgeSchedulingService scheduledPurgingService() {
        return purgeSchedulingService;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("target", PluginTarget.LEADER_ONLY)
                          .add("purgeSchedulingService", purgeSchedulingService)
                          .toString();
    }
}
