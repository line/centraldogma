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

import static com.linecorp.centraldogma.server.internal.storage.project.ProjectInitializer.INTERNAL_PROJ;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cronutils.utils.VisibleForTesting;
import com.google.common.util.concurrent.RateLimiter;
import com.spotify.futures.CompletableFutures;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.TooManyRequestsException;
import com.linecorp.centraldogma.server.QuotaConfig;
import com.linecorp.centraldogma.server.auth.Session;
import com.linecorp.centraldogma.server.auth.SessionManager;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * A {@link CommandExecutor} implementation which performs operations on the local storage.
 */
public class StandaloneCommandExecutor extends AbstractCommandExecutor {

    private static final Logger logger = LoggerFactory.getLogger(StandaloneCommandExecutor.class);

    private static final RateLimiter UNLIMITED = RateLimiter.create(Double.MAX_VALUE);

    private final ProjectManager projectManager;
    private final Executor repositoryWorker;
    @Nullable
    private final SessionManager sessionManager;
    // if permitsPerSecond is -1, a quota is checked by ZooKeeperCommandExecutor.
    private final double permitsPerSecond;
    private final MetadataService metadataService;

    @VisibleForTesting
    final Map<String, RateLimiter> writeRateLimiters;

    /**
     * Creates a new instance.
     *
     * @param projectManager the project manager for accessing the storage
     * @param repositoryWorker the executor which is used for performing storage operations
     * @param writeQuota the write quota for limiting {@link PushCommand}
     * @param sessionManager the session manager for creating/removing a session
     * @param onTakeLeadership the callback to be invoked after the replica has taken the leadership
     * @param onReleaseLeadership the callback to be invoked before the replica releases the leadership
     */
    public StandaloneCommandExecutor(ProjectManager projectManager,
                                     Executor repositoryWorker,
                                     @Nullable SessionManager sessionManager,
                                     @Nullable QuotaConfig writeQuota,
                                     @Nullable Consumer<CommandExecutor> onTakeLeadership,
                                     @Nullable Consumer<CommandExecutor> onReleaseLeadership) {
        this(projectManager, repositoryWorker, sessionManager,
             writeQuota != null ? writeQuota.permitsPerSecond() : 0,
             onTakeLeadership, onReleaseLeadership);
    }

    /**
     * Creates a new instance.
     *
     * @param projectManager the project manager for accessing the storage
     * @param repositoryWorker the executor which is used for performing storage operations
     * @param sessionManager the session manager for creating/removing a session
     * @param onTakeLeadership the callback to be invoked after the replica has taken the leadership
     * @param onReleaseLeadership the callback to be invoked before the replica releases the leadership
     */
    public StandaloneCommandExecutor(ProjectManager projectManager,
                                     Executor repositoryWorker,
                                     @Nullable SessionManager sessionManager,
                                     @Nullable Consumer<CommandExecutor> onTakeLeadership,
                                     @Nullable Consumer<CommandExecutor> onReleaseLeadership) {
        this(projectManager, repositoryWorker, sessionManager, -1, onTakeLeadership, onReleaseLeadership);
    }

    private StandaloneCommandExecutor(ProjectManager projectManager,
                                      Executor repositoryWorker,
                                      @Nullable SessionManager sessionManager,
                                      double permitsPerSecond,
                                      @Nullable Consumer<CommandExecutor> onTakeLeadership,
                                      @Nullable Consumer<CommandExecutor> onReleaseLeadership) {
        super(onTakeLeadership, onReleaseLeadership);
        this.projectManager = requireNonNull(projectManager, "projectManager");
        this.repositoryWorker = requireNonNull(repositoryWorker, "repositoryWorker");
        this.sessionManager = sessionManager;
        this.permitsPerSecond = permitsPerSecond;
        writeRateLimiters = new ConcurrentHashMap<>();
        metadataService = new MetadataService(projectManager, this);
    }

    @Override
    public int replicaId() {
        return 0;
    }

    @Override
    protected void doStart(@Nullable Runnable onTakeLeadership,
                           @Nullable Runnable onReleaseLeadership) {
        if (onTakeLeadership != null) {
            onTakeLeadership.run();
        }
    }

    @Override
    protected void doStop(@Nullable Runnable onReleaseLeadership) {
        if (onReleaseLeadership != null) {
            onReleaseLeadership.run();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> CompletableFuture<T> doExecute(Command<T> command) throws Exception {
        if (command instanceof CreateProjectCommand) {
            return (CompletableFuture<T>) createProject((CreateProjectCommand) command);
        }

        if (command instanceof RemoveProjectCommand) {
            return (CompletableFuture<T>) removeProject((RemoveProjectCommand) command);
        }

        if (command instanceof UnremoveProjectCommand) {
            return (CompletableFuture<T>) unremoveProject((UnremoveProjectCommand) command);
        }

        if (command instanceof PurgeProjectCommand) {
            return (CompletableFuture<T>) purgeProject((PurgeProjectCommand) command);
        }

        if (command instanceof CreateRepositoryCommand) {
            return (CompletableFuture<T>) createRepository((CreateRepositoryCommand) command);
        }

        if (command instanceof RemoveRepositoryCommand) {
            return (CompletableFuture<T>) removeRepository((RemoveRepositoryCommand) command);
        }

        if (command instanceof UnremoveRepositoryCommand) {
            return (CompletableFuture<T>) unremoveRepository((UnremoveRepositoryCommand) command);
        }

        if (command instanceof PurgeRepositoryCommand) {
            return (CompletableFuture<T>) purgeRepository((PurgeRepositoryCommand) command);
        }

        if (command instanceof PushCommand) {
            return (CompletableFuture<T>) push((PushCommand) command);
        }

        if (command instanceof CreateSessionCommand) {
            return (CompletableFuture<T>) createSession((CreateSessionCommand) command);
        }

        if (command instanceof RemoveSessionCommand) {
            return (CompletableFuture<T>) removeSession((RemoveSessionCommand) command);
        }

        throw new UnsupportedOperationException(command.toString());
    }

    // Project operations

    private CompletableFuture<Void> createProject(CreateProjectCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.create(c.projectName(), c.timestamp(), c.author());
            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Void> removeProject(RemoveProjectCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.remove(c.projectName());
            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Void> unremoveProject(UnremoveProjectCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.unremove(c.projectName());
            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Void> purgeProject(PurgeProjectCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.markForPurge(c.projectName());
            return null;
        }, repositoryWorker);
    }

    // Repository operations

    private CompletableFuture<Void> createRepository(CreateRepositoryCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.get(c.projectName()).repos().create(c.repositoryName(), c.timestamp(), c.author());
            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Void> removeRepository(RemoveRepositoryCommand c) {
        if (writeQuotaEnabled()) {
            writeRateLimiters.remove(rateLimiterKey(c.projectName(), c.repositoryName()));
        }
        return CompletableFuture.supplyAsync(() -> {
            projectManager.get(c.projectName()).repos().remove(c.repositoryName());
            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Void> unremoveRepository(UnremoveRepositoryCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.get(c.projectName()).repos().unremove(c.repositoryName());
            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Void> purgeRepository(PurgeRepositoryCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.get(c.projectName()).repos().markForPurge(c.repositoryName());
            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Revision> push(PushCommand c) {
        if (c.projectName().equals(INTERNAL_PROJ) || c.repositoryName().equals(Project.REPO_DOGMA) ||
            !writeQuotaEnabled()) {
            return push0(c);
        }

        final RateLimiter rateLimiter =
                writeRateLimiters.get(rateLimiterKey(c.projectName(), c.repositoryName()));
        if (rateLimiter != null) {
            return tryPush(c, rateLimiter);
        }

        return getRateLimiter(c.projectName(), c.repositoryName()).thenCompose(limiter -> tryPush(c, limiter));
    }

    private CompletableFuture<Revision> tryPush(PushCommand c, @Nullable RateLimiter rateLimiter) {
        if (rateLimiter == null || rateLimiter == UNLIMITED || rateLimiter.tryAcquire()) {
            return push0(c);
        } else {
            return CompletableFutures.exceptionallyCompletedFuture(
                    new TooManyRequestsException("commits", c.executionPath(), rateLimiter.getRate()));
        }
    }

    private CompletableFuture<Revision> push0(PushCommand c) {
        return repo(c).commit(c.baseRevision(), c.timestamp(),
                              c.author(), c.summary(), c.detail(), c.markup(), c.changes());
    }

    private CompletableFuture<RateLimiter> getRateLimiter(String projectName, String repoName) {
        return metadataService.getRepo(projectName, repoName).thenApply(meta -> {
            setWriteQuota(projectName, repoName, meta.writeQuota());
            return writeRateLimiters.get(rateLimiterKey(projectName, repoName));
        });
    }

    @Override
    public final void setWriteQuota(String projectName, String repoName, @Nullable QuotaConfig writeQuota) {
        final double permitsForRepo = writeQuota != null ? writeQuota.permitsPerSecond() : 0;
        final double permitsPerSecond = permitsForRepo != 0 ? permitsForRepo : this.permitsPerSecond;

        writeRateLimiters.compute(rateLimiterKey(projectName, repoName), (key, rateLimiter) -> {
            if (permitsPerSecond == 0) {
                return UNLIMITED;
            }

            if (rateLimiter == null) {
                return RateLimiter.create(permitsPerSecond);
            } else {
                rateLimiter.setRate(permitsPerSecond);
                return rateLimiter;
            }
        });
    }

    private static String rateLimiterKey(String projectName, String repoName) {
        return projectName + '/' + repoName;
    }

    private boolean writeQuotaEnabled() {
        return Double.compare(permitsPerSecond, -1) > 0;
    }

    private Repository repo(RepositoryCommand<?> c) {
        return projectManager.get(c.projectName()).repos().get(c.repositoryName());
    }

    private CompletableFuture<Void> createSession(CreateSessionCommand c) {
        if (sessionManager == null) {
            // Security has been disabled for this replica.
            return CompletableFuture.completedFuture(null);
        }

        final Session session = c.session();
        return sessionManager.create(session).exceptionally(cause -> {
            logger.warn("Failed to replicate a session creation: {}", session, cause);
            return null;
        });
    }

    private CompletableFuture<Void> removeSession(RemoveSessionCommand c) {
        if (sessionManager == null) {
            return CompletableFuture.completedFuture(null);
        }

        final String sessionId = c.sessionId();
        return sessionManager.delete(sessionId).exceptionally(cause -> {
            logger.warn("Failed to replicate a session removal: {}", sessionId, cause);
            return null;
        });
    }
}
