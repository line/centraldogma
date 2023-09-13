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

package com.linecorp.centraldogma.server.plugin;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ScheduledExecutorService;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A context that is used to pass when calling {@link AllReplicasPlugin#init(PluginInitContext)}.
 */
public final class PluginInitContext extends PluginContext {

    private final ServerBuilder serverBuilder;

    /**
     * Creates a new instance.
     */
    public PluginInitContext(CentralDogmaConfig config,
                             ProjectManager projectManager,
                             CommandExecutor commandExecutor,
                             MeterRegistry meterRegistry,
                             ScheduledExecutorService purgeWorker, ServerBuilder serverBuilder) {
        super(config, projectManager, commandExecutor, meterRegistry, purgeWorker);
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
    }

    /**
     * Returns the {@link ServerBuilder} of the Central Dogma server.
     */
    public ServerBuilder serverBuilder() {
        return serverBuilder;
    }
}
