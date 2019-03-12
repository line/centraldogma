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

import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;

public final class DefaultMirroringServicePlugin implements Plugin {

    // TODO(hyangtack) Need volatile?
    private volatile DefaultMirroringService mirroringService;

    @Override
    public PluginTarget target() {
        return PluginTarget.LEADER_ONLY;
    }

    @Override
    public CompletionStage<Void> start(PluginContext context) {
        requireNonNull(context, "context");

        DefaultMirroringService mirroringService = this.mirroringService;
        if (mirroringService == null) {
            final CentralDogmaConfig cfg = context.config();
            mirroringService = new DefaultMirroringService(new File(cfg.dataDir(), "_mirrors"),
                                                           context.projectManager(),
                                                           cfg.numMirroringThreads(),
                                                           cfg.maxNumFilesPerMirror(),
                                                           cfg.maxNumBytesPerMirror());
            this.mirroringService = mirroringService;
        }
        mirroringService.start(context.commandExecutor());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> stop(PluginContext context) {
        final DefaultMirroringService mirroringService = this.mirroringService;
        if (mirroringService != null && mirroringService.isStarted()) {
            mirroringService.stop();
        }
        return CompletableFuture.completedFuture(null);
    }
}
