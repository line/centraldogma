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
package com.linecorp.centraldogma.server.internal.mirror;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.CentralDogmaConfigSpec;
import com.linecorp.centraldogma.server.ZoneConfigSpec;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfigSpec;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginConfig;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;

public final class DefaultMirroringServicePlugin implements Plugin {

    @Nullable
    public static MirroringServicePluginConfig mirrorConfig(CentralDogmaConfigSpec config) {
        return (MirroringServicePluginConfig) config.pluginConfigMap().get(MirroringServicePluginConfig.class);
    }

    @Nullable
    private volatile MirrorSchedulingService mirroringService;

    @Nullable
    private PluginTarget pluginTarget;

    @Override
    public PluginTarget target(CentralDogmaConfigSpec config) {
        requireNonNull(config, "config");
        if (pluginTarget != null) {
            return pluginTarget;
        }

        final MirroringServicePluginConfig mirrorConfig = mirrorConfig(config);
        if (mirrorConfig != null && mirrorConfig.zonePinned()) {
            pluginTarget = PluginTarget.ZONE_LEADER_ONLY;
        } else {
            pluginTarget = PluginTarget.LEADER_ONLY;
        }
        return pluginTarget;
    }

    @Override
    public synchronized CompletionStage<Void> start(PluginContext context) {
        requireNonNull(context, "context");

        MirrorSchedulingService mirroringService = this.mirroringService;
        if (mirroringService == null) {
            final CentralDogmaConfigSpec cfg = context.config();
            final MirroringServicePluginConfig mirroringServicePluginConfig = mirrorConfig(cfg);
            final int numThreads;
            final int maxNumFilesPerMirror;
            final long maxNumBytesPerMirror;
            final ZoneConfigSpec zoneConfig;

            if (mirroringServicePluginConfig != null) {
                numThreads = mirroringServicePluginConfig.numMirroringThreads();
                maxNumFilesPerMirror = mirroringServicePluginConfig.maxNumFilesPerMirror();
                maxNumBytesPerMirror = mirroringServicePluginConfig.maxNumBytesPerMirror();
                if (mirroringServicePluginConfig.zonePinned()) {
                    zoneConfig = cfg.zone();
                    assert zoneConfig != null : "zonePinned is enabled but no zone configuration found";
                } else {
                    zoneConfig = null;
                }
            } else {
                numThreads = MirroringServicePluginConfig.INSTANCE.numMirroringThreads();
                maxNumFilesPerMirror = MirroringServicePluginConfig.INSTANCE.maxNumFilesPerMirror();
                maxNumBytesPerMirror = MirroringServicePluginConfig.INSTANCE.maxNumBytesPerMirror();
                zoneConfig = null;
            }
            mirroringService = new MirrorSchedulingService(new File(cfg.dataDir(), "_mirrors"),
                                                           context.projectManager(),
                                                           context.meterRegistry(),
                                                           numThreads,
                                                           maxNumFilesPerMirror,
                                                           maxNumBytesPerMirror, zoneConfig);
            this.mirroringService = mirroringService;
        }
        mirroringService.start(context.commandExecutor());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> stop(PluginContext context) {
        final MirrorSchedulingService mirroringService = this.mirroringService;
        if (mirroringService != null && mirroringService.isStarted()) {
            mirroringService.stop();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Class<? extends PluginConfig> configType() {
        return MirroringServicePluginConfigSpec.class;
    }

    @Nullable
    public MirrorSchedulingService mirroringService() {
        return mirroringService;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("configType", configType().getName())
                          .add("target", pluginTarget)
                          .toString();
    }
}
