/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.server.command;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.StartStopSupport;
import com.linecorp.centraldogma.common.ReadOnlyException;
import com.linecorp.centraldogma.server.management.ReplicationStatus;
import com.linecorp.centraldogma.server.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.metadata.RepositoryMetadata;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

/**
 * Helps to implement a concrete {@link CommandExecutor}.
 */
public abstract class AbstractCommandExecutor implements CommandExecutor {

    private static final Logger logger = LoggerFactory.getLogger(AbstractCommandExecutor.class);

    private final ProjectManager projectManager;
    @Nullable
    private final Consumer<CommandExecutor> onTakeLeadership;
    @Nullable
    private final Consumer<CommandExecutor> onReleaseLeadership;
    @Nullable
    private final Consumer<CommandExecutor> onTakeZoneLeadership;
    @Nullable
    private final Consumer<CommandExecutor> onReleaseZoneLeadership;

    private final CommandExecutorStartStop startStop = new CommandExecutorStartStop();
    private volatile boolean started;
    private volatile boolean writable = true;
    private final AtomicInteger numPendingStopRequests = new AtomicInteger();
    private final CommandExecutorStatusManager statusManager;

    @Nullable
    private BiFunction<String, String, CompletableFuture<RepositoryMetadata>> repositoryMetadataSupplier;

    /**
     * Creates a new instance.
     *
     * @param projectManager the {@link ProjectManager} that manages the projects and repositories
     * @param onTakeLeadership the callback to be invoked after the replica has taken the leadership
     * @param onReleaseLeadership the callback to be invoked before the replica releases the leadership
     * @param onTakeZoneLeadership the callback to be invoked after the replica has taken the zone leadership
     * @param onReleaseZoneLeadership the callback to be invoked before the replica releases the zone leadership
     */
    protected AbstractCommandExecutor(ProjectManager projectManager,
                                      @Nullable Consumer<CommandExecutor> onTakeLeadership,
                                      @Nullable Consumer<CommandExecutor> onReleaseLeadership,
                                      @Nullable Consumer<CommandExecutor> onTakeZoneLeadership,
                                      @Nullable Consumer<CommandExecutor> onReleaseZoneLeadership) {
        this.projectManager = requireNonNull(projectManager, "projectManager");
        this.onTakeLeadership = onTakeLeadership;
        this.onReleaseLeadership = onReleaseLeadership;
        this.onTakeZoneLeadership = onTakeZoneLeadership;
        this.onReleaseZoneLeadership = onReleaseZoneLeadership;
        statusManager = new CommandExecutorStatusManager(this);
    }

    ProjectManager projectManager() {
        return projectManager;
    }

    @Override
    public final boolean isStarted() {
        return started;
    }

    protected final boolean isStopping() {
        return numPendingStopRequests.get() > 0;
    }

    @Override
    public final CompletableFuture<Void> start() {
        return startStop.start(false).thenRun(() -> {
            started = true;
            if (!writable) {
                logger.warn("Started a command executor with read-only mode.");
            }
        });
    }

    protected abstract void doStart(@Nullable Runnable onTakeLeadership,
                                    @Nullable Runnable onReleaseLeadership,
                                    @Nullable Runnable onTakeZoneLeadership,
                                    @Nullable Runnable onReleaseZoneLeadership) throws Exception;

    @Override
    public final CompletableFuture<Void> stop() {
        started = false;
        numPendingStopRequests.incrementAndGet();
        return startStop.stop().thenRun(numPendingStopRequests::decrementAndGet);
    }

    protected abstract void doStop(@Nullable Runnable onReleaseLeadership,
                                   @Nullable Runnable onReleaseZoneLeadership) throws Exception;

    @Override
    public final boolean isWritable() {
        return isStarted() && writable;
    }

    @Override
    public final void setWritable(boolean writable) {
        this.writable = writable;
    }

    protected void throwExceptionIfRepositoryNotWritable(Command<?> command) throws Exception {
        if (command instanceof NormalizableCommit) {
            assert command instanceof RepositoryCommand;
            final RepositoryCommand<?> repositoryCommand = (RepositoryCommand<?>) command;
            if (InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(repositoryCommand.projectName())) {
                return;
            }
            String repoName = repositoryCommand.repositoryName();
            if (Project.REPO_META.equals(repoName)) {
                // Use REPO_DOGMA for the meta repository because the meta repository will be removed.
                repoName = Project.REPO_DOGMA;
            }

            final ProjectMetadata metadata = projectManager.get(repositoryCommand.projectName()).metadata();
            assert metadata != null;
            final RepositoryMetadata repositoryMetadata = metadata.repos().get(repoName);
            if (repositoryMetadata == null) {
                // The repository metadata is not found, so it is writable.
                return;
            }
            final ReplicationStatus replicationStatus = repositoryMetadata.replicationStatus();
            if (replicationStatus != null && !replicationStatus.writable()) {
                throw new ReadOnlyException(
                        "The repository is not writable. command: " + repositoryCommand);
            }
        }
    }

    @Override
    public void setRepositoryMetadataSupplier(
            BiFunction<String, String, CompletableFuture<RepositoryMetadata>> supplier) {
        repositoryMetadataSupplier = requireNonNull(supplier, "supplier");
    }

    /**
     * Returns the {@link RepositoryMetadata} of the specified repository.
     */
    @Nullable
    protected CompletableFuture<RepositoryMetadata> repositoryMetadata(String projectName, String repoName) {
        if (repositoryMetadataSupplier == null) {
            return null;
        }
        return repositoryMetadataSupplier.apply(projectName, repoName);
    }

    @Override
    public final <T> CompletableFuture<T> execute(Command<T> command) {
        requireNonNull(command, "command");
        if (!isStarted()) {
            throw new ReadOnlyException("running in read-only mode. command: " + command);
        }
        if (!writable && !(command instanceof SystemAdministrativeCommand)) {
            // Reject all commands except for AdministrativeCommand when the replica is in read-only mode.
            // AdministrativeCommand is allowed because it is used to change the read-only mode or migrate
            // metadata under maintenance mode.
            throw new ReadOnlyException("running in read-only mode. command: " + command);
        }

        try {
            return doExecute(command);
        } catch (Throwable t) {
            final CompletableFuture<T> f = new CompletableFuture<>();
            f.completeExceptionally(t);
            return f;
        }
    }

    protected abstract <T> CompletableFuture<T> doExecute(Command<T> command) throws Exception;

    @Override
    public CommandExecutorStatusManager statusManager() {
        return statusManager;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("writable", isWritable())
                          .add("replicating", started)
                          .toString();
    }

    private final class CommandExecutorStartStop extends StartStopSupport<Void, Void, Void, Void> {

        CommandExecutorStartStop() {
            super(ForkJoinPool.commonPool());
        }

        @Override
        protected CompletionStage<Void> doStart(@Nullable Void unused) throws Exception {
            return execute("command-executor", () -> {
                try {
                    AbstractCommandExecutor.this.doStart(toRunnable(onTakeLeadership),
                                                         toRunnable(onReleaseLeadership),
                                                         toRunnable(onTakeZoneLeadership),
                                                         toRunnable(onReleaseZoneLeadership));
                } catch (Exception e) {
                    Exceptions.throwUnsafely(e);
                }
            });
        }

        @Override
        protected CompletionStage<Void> doStop(@Nullable Void unused) throws Exception {
            return execute("command-executor-shutdown", () -> {
                try {
                    AbstractCommandExecutor.this.doStop(toRunnable(onReleaseLeadership),
                                                        toRunnable(onReleaseZoneLeadership));
                } catch (Exception e) {
                    Exceptions.throwUnsafely(e);
                }
            });
        }

        @Nullable
        private Runnable toRunnable(@Nullable Consumer<CommandExecutor> callback) {
            return callback != null ? () -> callback.accept(AbstractCommandExecutor.this) : null;
        }

        private CompletionStage<Void> execute(String threadNamePrefix, Runnable task) {
            final CompletableFuture<Void> future = new CompletableFuture<>();
            final String threadName = threadNamePrefix + "-0x" +
                                      Long.toHexString(AbstractCommandExecutor.this.hashCode() & 0xFFFFFFFFL);
            final Thread thread = new Thread(() -> {
                try {
                    task.run();
                    future.complete(null);
                } catch (Throwable cause) {
                    future.completeExceptionally(cause);
                }
            }, threadName);
            thread.setContextClassLoader(CommandExecutorStartStop.class.getClassLoader());
            thread.start();
            return future;
        }
    }
}
