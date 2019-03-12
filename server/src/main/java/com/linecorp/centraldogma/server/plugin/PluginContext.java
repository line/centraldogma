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

import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;

/**
 * A class which is used to pass internally-created instances into the {@link Plugin}.
 */
public final class PluginContext {

    private final CentralDogmaConfig config;
    private final ProjectManager projectManager;
    private final CommandExecutor commandExecutor;

    /**
     * Creates a new instance.
     *
     * @param config the Central Dogma configuration
     * @param projectManager the instance which has the operations for the {@link Project}s
     * @param commandExecutor the executor which executes the {@link Command}s
     */
    public PluginContext(CentralDogmaConfig config,
                         ProjectManager projectManager,
                         CommandExecutor commandExecutor) {
        this.config = requireNonNull(config, "config");
        this.projectManager = requireNonNull(projectManager, "projectManager");
        this.commandExecutor = requireNonNull(commandExecutor, "commandExecutor");
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
}
