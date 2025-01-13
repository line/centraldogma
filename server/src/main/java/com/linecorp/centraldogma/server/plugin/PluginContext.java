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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ScheduledExecutorService;

import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.mirror.MirrorAccessController;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A class which is used to pass internally-created instances into the {@link Plugin}.
 */
public class PluginContext {

    private final CentralDogmaConfig config;
    private final ProjectManager projectManager;
    private final CommandExecutor commandExecutor;
    private final MeterRegistry meterRegistry;
    private final ScheduledExecutorService purgeWorker;
    private final InternalProjectInitializer internalProjectInitializer;
    private final MirrorAccessController mirrorAccessController;

    /**
     * Creates a new instance.
     *
     * @param config the Central Dogma configuration
     * @param projectManager the instance which has the operations for the {@link Project}s
     * @param commandExecutor the executor which executes the {@link Command}s
     * @param meterRegistry the {@link MeterRegistry} of the Central Dogma server
     * @param purgeWorker the {@link ScheduledExecutorService} for the purging service
     * @param internalProjectInitializer the initializer for the internal projects
     * @param mirrorAccessController the controller which controls the access to the remote repos of mirrors
     */
    public PluginContext(CentralDogmaConfig config,
                         ProjectManager projectManager,
                         CommandExecutor commandExecutor,
                         MeterRegistry meterRegistry,
                         ScheduledExecutorService purgeWorker,
                         InternalProjectInitializer internalProjectInitializer,
                         MirrorAccessController mirrorAccessController) {
        this.config = requireNonNull(config, "config");
        this.projectManager = requireNonNull(projectManager, "projectManager");
        this.commandExecutor = requireNonNull(commandExecutor, "commandExecutor");
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        this.purgeWorker = requireNonNull(purgeWorker, "purgeWorker");
        this.internalProjectInitializer = requireNonNull(internalProjectInitializer,
                                                         "internalProjectInitializer");
        this.mirrorAccessController = requireNonNull(mirrorAccessController, "mirrorAccessController");
    }

    /**
     * Returns the {@link CentralDogmaConfig}.
     */
    public CentralDogmaConfig config() {
        return config;
    }

    /**
     * Returns the {@link ProjectManager}.
     */
    public ProjectManager projectManager() {
        return projectManager;
    }

    /**
     * Returns the {@link CommandExecutor}.
     */
    public CommandExecutor commandExecutor() {
        return commandExecutor;
    }

    /**
     * Returns the {@link MeterRegistry}.
     */
    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    /**
     * Returns the {@link ScheduledExecutorService} of {@code purgeWorker}.
     */
    public ScheduledExecutorService purgeWorker() {
        return purgeWorker;
    }

    /**
     * Returns the {@link InternalProjectInitializer}.
     */
    public InternalProjectInitializer internalProjectInitializer() {
        return internalProjectInitializer;
    }

    /**
     * Returns the {@link MirrorAccessController}.
     */
    public MirrorAccessController mirrorAccessController() {
        return mirrorAccessController;
    }
}
