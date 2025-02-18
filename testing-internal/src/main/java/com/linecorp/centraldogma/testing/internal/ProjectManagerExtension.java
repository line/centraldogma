/*
 * Copyright 2020 LINE Corporation
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

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.management.ServerStatusManager;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.testing.junit.AbstractAllOrEachExtension;

import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * A JUnit {@link Extension} that starts a {@link ProjectManager}.
 *
 * <pre>{@code
 * > class MyTest {
 * >     @RegisterExtension
 * >     static final ProjectManagerExtension extension = new ProjectManagerExtension();
 * >
 * >     @Test
 * >     void test() throws Exception {
 * >         MetadataService mds = new MetadataService(extension.projectManager(), extension.executor());
 * >         ...
 * >     }
 * > }
 * }</pre>
 */
public class ProjectManagerExtension extends AbstractAllOrEachExtension {

    private ProjectManager projectManager;
    private CommandExecutor executor;
    private ScheduledExecutorService purgeWorker;
    private InternalProjectInitializer internalProjectInitializer;

    private final TemporaryFolder tempDir = new TemporaryFolder();
    private File dataDir;

    /**
     * Configures an {@link Executor}, {@link ProjectManager} and {@link CommandExecutor},
     * then starts the {@link CommandExecutor} and initializes internal projects.
     */
    @Override
    public void before(ExtensionContext context) throws Exception {
        tempDir.create();
        dataDir = tempDir.newFolder().toFile();
        final Executor repositoryWorker = newWorker();
        purgeWorker = Executors.newSingleThreadScheduledExecutor(
                new DefaultThreadFactory("purge-worker", true));
        projectManager = newProjectManager(repositoryWorker, purgeWorker);
        executor = newCommandExecutor(projectManager, repositoryWorker, dataDir);

        executor.start().get();
        internalProjectInitializer = new InternalProjectInitializer(executor, projectManager);
        internalProjectInitializer.initialize();
        final MetadataService mds = new MetadataService(projectManager, executor, internalProjectInitializer);
        executor.setRepositoryMetadataSupplier(mds::getRepo);

        afterExecutorStarted();
    }

    /**
     * Stops the {@link CommandExecutor} and the {@link ProjectManager}.
     */
    @Override
    public void after(ExtensionContext context) throws Exception {
        tempDir.delete();
        executor.stop();
        purgeWorker.shutdownNow();
        projectManager.close(ShuttingDownException::new);
    }

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
     * Returns a {@link ScheduledExecutorService} to purge a project.
     */
    public ScheduledExecutorService purgeWorker() {
        return purgeWorker;
    }

    /**
     * Returns an {@link InternalProjectInitializer}.
     */
    public InternalProjectInitializer internalProjectInitializer() {
        return internalProjectInitializer;
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
    protected ProjectManager newProjectManager(Executor repositoryWorker, Executor purgeWorker) {
        try {
            return new DefaultProjectManager(dataDir, repositoryWorker,
                                             purgeWorker, NoopMeterRegistry.get(),
                                             CentralDogmaBuilder.DEFAULT_REPOSITORY_CACHE_SPEC);
        } catch (Exception e) {
            // Should not reach here.
            throw new Error(e);
        }
    }

    /**
     * Override this method to customize a {@link CommandExecutor}.
     */
    protected CommandExecutor newCommandExecutor(ProjectManager projectManager, Executor worker, File dataDir) {
        return new StandaloneCommandExecutor(projectManager, worker, new ServerStatusManager(dataDir), null,
                                             null, null, null, null, null);
    }
}
