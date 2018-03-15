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

import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.command.ProjectInitializer;
import com.linecorp.centraldogma.server.internal.command.ProjectInitializingCommandExecutor;
import com.linecorp.centraldogma.server.internal.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;

/**
 * JUnit {@link Rule} that starts a {@link ProjectManager}.
 *
 * <pre>{@code
 * > public class MyTest {
 * >     @ClassRule
 * >     public static final ProjectManagerRule rule = new ProjectManagerRule();
 * >
 * >     @Test
 * >     public void test() throws Exception {
 * >         MetadataService mds = new MetadataService(rule.projectManager(), rule.executor());
 * >         ...
 * >     }
 * > }
 * }</pre>
 */
public class ProjectManagerRule extends TemporaryFolder {

    private ProjectManager projectManager;
    private CommandExecutor executor;

    /**
     * Returns a {@link ProjectManager}.
     */
    public ProjectManager projectManager() {
        return projectManager;
    }

    /**
     * Returns a {@link CommandExecutor}.
     */
    public CommandExecutor executor() {
        return executor;
    }

    /**
     * Configures an {@link Executor}, {@link ProjectManager} and {@link CommandExecutor}, then starts the
     * {@link CommandExecutor} and initializes internal projects.
     */
    @Override
    protected final void before() throws Throwable {
        super.before();

        final Executor worker = newWorker();
        projectManager = newProjectManager(worker);
        executor = newCommandExecutor(projectManager, worker);

        executor.start(null, null);
        ProjectInitializer.initializeInternalProject(executor);

        afterExecutorStarted();
    }

    /**
     * Override this method to configure a project after the executor started.
     */
    protected void afterExecutorStarted() {}

    /**
     * Override this method to customize an {@link Executor}.
     */
    protected Executor newWorker() {
        return ForkJoinPool.commonPool();
    }

    /**
     * Override this method to customize a {@link ProjectManager}.
     */
    protected ProjectManager newProjectManager(Executor worker) {
        try {
            return new DefaultProjectManager(newFolder(), worker, null);
        } catch (Exception e) {
            // Should not reach here.
            throw new Error(e);
        }
    }

    /**
     * Override this method to customize a {@link CommandExecutor}.
     */
    protected CommandExecutor newCommandExecutor(ProjectManager projectManager, Executor worker) {
        return new ProjectInitializingCommandExecutor(
                new StandaloneCommandExecutor(projectManager, null, worker));
    }

    /**
     * Stops the {@link CommandExecutor} and the {@link ProjectManager}.
     */
    @Override
    protected void after() {
        super.after();
        executor.stop();
        projectManager.close();
    }
}
