/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.centraldogma.testing.internal;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import org.junit.rules.TemporaryFolder;

import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.command.ProjectInitializer;
import com.linecorp.centraldogma.server.internal.command.ProjectInitializingCommandExecutor;
import com.linecorp.centraldogma.server.internal.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;

public class ProjectManagerRule extends TemporaryFolder {

    private ProjectManager projectManager;
    private CommandExecutor executor;

    public ProjectManager projectManager() {
        return projectManager;
    }

    public CommandExecutor executor() {
        return executor;
    }

    @Override
    protected final void before() throws Throwable {
        super.before();

        final Executor worker = configureWorker();
        projectManager = configureProjectManager(worker);
        executor = configureCommandExecutor(projectManager, worker);

        initialize();
    }

    protected void initialize() {
        executor.start(null, null);
        ProjectInitializer.initializeInternalProject(executor);
    }

    protected Executor configureWorker() {
        return ForkJoinPool.commonPool();
    }

    protected ProjectManager configureProjectManager(Executor worker) {
        try {
            return new DefaultProjectManager(newFolder(), worker, null);
        } catch (Exception e) {
            // Should not reach here.
            throw new Error(e);
        }
    }

    protected CommandExecutor configureCommandExecutor(ProjectManager projectManager, Executor worker) {
        return new ProjectInitializingCommandExecutor(
                new StandaloneCommandExecutor(projectManager, null, worker));
    }

    @Override
    protected void after() {
        super.after();
        executor.stop();
    }
}
