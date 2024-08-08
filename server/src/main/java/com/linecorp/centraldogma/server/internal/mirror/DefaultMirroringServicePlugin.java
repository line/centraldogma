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
package com.linecorp.centraldogma.server.internal.mirror;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;

public final class DefaultMirroringServicePlugin implements Plugin {

    @Nullable
    private volatile DefaultMirroringService mirroringService;

    @Override
    public PluginTarget target() {
        return PluginTarget.LEADER_ONLY;
    }

    @Override
    public synchronized CompletionStage<Void> start(PluginContext context) {
        requireNonNull(context, "context");

        DefaultMirroringService mirroringService = this.mirroringService;
        if (mirroringService == null) {
            final CentralDogmaConfig cfg = context.config();
            final MirroringServicePluginConfig mirroringServicePluginConfig =
                    (MirroringServicePluginConfig) cfg.pluginConfigMap().get(configType());
            final int numThreads;
            final int maxNumFilesPerMirror;
            final long maxNumBytesPerMirror;

            if (mirroringServicePluginConfig != null) {
                numThreads = mirroringServicePluginConfig.numMirroringThreads();
                maxNumFilesPerMirror = mirroringServicePluginConfig.maxNumFilesPerMirror();
                maxNumBytesPerMirror = mirroringServicePluginConfig.maxNumBytesPerMirror();
            } else {
                numThreads = MirroringServicePluginConfig.INSTANCE.numMirroringThreads();
                maxNumFilesPerMirror = MirroringServicePluginConfig.INSTANCE.maxNumFilesPerMirror();
                maxNumBytesPerMirror = MirroringServicePluginConfig.INSTANCE.maxNumBytesPerMirror();
            }
            mirroringService = new DefaultMirroringService(new File(cfg.dataDir(), "_mirrors"),
                                                           context.projectManager(),
                                                           context.meterRegistry(),
                                                           numThreads,
                                                           maxNumFilesPerMirror,
                                                           maxNumBytesPerMirror);
            this.mirroringService = mirroringService;
        }
        mirroringService.start(context.commandExecutor());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> stop(PluginContext context) {
        final DefaultMirroringService mirroringService = this.mirroringService;
        if (mirroringService != null && mirroringService.isStarted()) {
            mirroringService.stop();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Class<?> configType() {
        return MirroringServicePluginConfig.class;
    }

    @Nullable
    public DefaultMirroringService mirroringService() {
        return mirroringService;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("configType", configType())
                          .add("target", target())
                          .toString();
    }
}
