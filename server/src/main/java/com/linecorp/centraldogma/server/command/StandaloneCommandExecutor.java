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
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.ReadOnlyException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.auth.Session;
import com.linecorp.centraldogma.server.auth.SessionManager;
import com.linecorp.centraldogma.server.internal.management.RepoStatusManager;
import com.linecorp.centraldogma.server.internal.management.ServerStatusManager;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.encryption.WrappedDekDetails;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;

/**
 * A {@link CommandExecutor} implementation which performs operations on the local storage.
 */
public class StandaloneCommandExecutor extends AbstractCommandExecutor {

    private static final Logger logger = LoggerFactory.getLogger(StandaloneCommandExecutor.class);

    private final ProjectManager projectManager;
    private final Executor repositoryWorker;
    @Nullable
    private final SessionManager sessionManager;
    private final EncryptionStorageManager encryptionStorageManager;
    private final ServerStatusManager serverStatusManager;
    private final RepoStatusManager repoStatusManager;

    /**
     * Creates a new instance.
     *
     * @param projectManager the project manager for accessing the storage
     * @param repositoryWorker the executor which is used for performing storage operations
     * @param serverStatusManager the server status manager for updating the server status
     * @param repoStatusManager the repository status manager for updating the project and repository status
     * @param sessionManager the session manager for creating/removing a session
     * @param onTakeLeadership the callback to be invoked after the replica has taken the leadership
     * @param onReleaseLeadership the callback to be invoked before the replica releases the leadership
     * @param onTakeZoneLeadership the callback to be invoked after the replica has taken the zone leadership
     * @param onReleaseZoneLeadership the callback to be invoked before the replica releases the zone leadership
     */
    public StandaloneCommandExecutor(ProjectManager projectManager,
                                     Executor repositoryWorker,
                                     ServerStatusManager serverStatusManager,
                                     RepoStatusManager repoStatusManager,
                                     @Nullable SessionManager sessionManager,
                                     EncryptionStorageManager encryptionStorageManager,
                                     @Nullable Consumer<CommandExecutor> onTakeLeadership,
                                     @Nullable Consumer<CommandExecutor> onReleaseLeadership,
                                     @Nullable Consumer<CommandExecutor> onTakeZoneLeadership,
                                     @Nullable Consumer<CommandExecutor> onReleaseZoneLeadership) {
        super(onTakeLeadership, onReleaseLeadership, onTakeZoneLeadership, onReleaseZoneLeadership);
        this.projectManager = requireNonNull(projectManager, "projectManager");
        this.repositoryWorker = requireNonNull(repositoryWorker, "repositoryWorker");
        this.serverStatusManager = requireNonNull(serverStatusManager, "serverStatusManager");
        this.repoStatusManager = requireNonNull(repoStatusManager, "repoStatusManager");
        this.sessionManager = sessionManager;
        this.encryptionStorageManager = requireNonNull(encryptionStorageManager, "encryptionStorageManager");
    }

    @Override
    public int replicaId() {
        return 0;
    }

    @Override
    protected void doStart(@Nullable Runnable onTakeLeadership,
                           @Nullable Runnable onReleaseLeadership,
                           @Nullable Runnable onTakeZoneLeadership,
                           @Nullable Runnable onReleaseZoneLeadership) {
        if (onTakeLeadership != null) {
            onTakeLeadership.run();
        }
        if (onTakeZoneLeadership != null) {
            onTakeZoneLeadership.run();
        }
    }

    @Override
    protected void doStop(@Nullable Runnable onReleaseLeadership, @Nullable Runnable onReleaseZoneLeadership) {
        if (onReleaseLeadership != null) {
            onReleaseLeadership.run();
        }
        if (onReleaseZoneLeadership != null) {
            onReleaseZoneLeadership.run();
        }
    }

    @Override
    protected <T> CompletableFuture<T> doExecute(ExecutionContext ctx, Command<T> command) throws Exception {
        throwExceptionIfRepositoryNotWritable(ctx, command);
        return doExecute0(ctx, command);
    }

    private void throwExceptionIfRepositoryNotWritable(ExecutionContext ctx, Command<?> command)
            throws Exception {
        if (!ctx.isReplay() &&
            (command instanceof NormalizableCommit || command instanceof PushAsIsCommand)) {
            final RepositoryCommand<?> repositoryCommand = (RepositoryCommand<?>) command;
            if (InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(repositoryCommand.projectName())) {
                return;
            }
            String repoName = repositoryCommand.repositoryName();
            if (Project.REPO_META.equals(repoName)) {
                // Use REPO_DOGMA for the meta repository because the meta repository will be removed.
                repoName = Project.REPO_DOGMA;
            }

            final boolean writable = repoStatusManager.isWritable(repositoryCommand.projectName(), repoName);
            if (!writable) {
                throw new ReadOnlyException("The repository is in read-only. command: " + repositoryCommand);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> doExecute0(ExecutionContext ctx, Command<T> command) throws Exception {
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

        if (command instanceof MigrateToEncryptedRepositoryCommand) {
            if (!encryptionStorageManager.enabled()) {
                throw new IllegalStateException(
                        "Encryption is not enabled. command: " + command);
            }
            return (CompletableFuture<T>) migrateToEncryptedRepository(
                    (MigrateToEncryptedRepositoryCommand) command);
        }

        if (command instanceof FallbackToFileRepositoryCommand) {
            return (CompletableFuture<T>) fallbackToFileRepository((FallbackToFileRepositoryCommand) command);
        }

        if (command instanceof NormalizingPushCommand) {
            return (CompletableFuture<T>) push((NormalizingPushCommand) command, true);
        }

        if (command instanceof PushAsIsCommand) {
            return (CompletableFuture<T>) push((PushAsIsCommand) command, false)
                    .thenApply(CommitResult::revision);
        }

        if (command instanceof TransformCommand) {
            return (CompletableFuture<T>) push((TransformCommand) command, true);
        }

        if (command instanceof RewrapAllKeysCommand) {
            return (CompletableFuture<T>) rewrapAllKeys();
        }

        if (command instanceof RotateWdekCommand) {
            return (CompletableFuture<T>) rotateWdek((RotateWdekCommand) command);
        }

        if (command instanceof RotateSessionMasterKeyCommand) {
            return (CompletableFuture<T>) rotateSessionMasterKey((RotateSessionMasterKeyCommand) command);
        }

        if (command instanceof CreateSessionCommand) {
            return (CompletableFuture<T>) createSession((CreateSessionCommand) command);
        }

        if (command instanceof RemoveSessionCommand) {
            return (CompletableFuture<T>) removeSession((RemoveSessionCommand) command);
        }

        if (command instanceof CreateSessionMasterKeyCommand) {
            return (CompletableFuture<T>) createSessionMasterKey((CreateSessionMasterKeyCommand) command);
        }

        if (command instanceof UpdateServerStatusCommand) {
            return (CompletableFuture<T>) updateServerStatus((UpdateServerStatusCommand) command);
        }

        if (command instanceof UpdateProjectStatusCommand) {
            return (CompletableFuture<T>) updateProjectStatus((UpdateProjectStatusCommand) command);
        }

        if (command instanceof UpdateRepositoryStatusCommand) {
            return (CompletableFuture<T>) updateRepositoryStatus((UpdateRepositoryStatusCommand) command);
        }

        if (command instanceof ForcePushCommand) {
            // TODO(minwoox): Should we prevent executing when the replication status is READ_ONLY?
            //noinspection TailRecursion
            return doExecute0(ctx, ((ForcePushCommand<T>) command).delegate());
        }

        if (command instanceof RecoverRepositoryRequestCommand) {
            // Applied as a no-op on every replica; the source replica reacts to it in the ZooKeeper layer.
            return (CompletableFuture<T>) CompletableFuture.completedFuture(null);
        }

        if (command instanceof RecoverRepositoryCommand) {
            return (CompletableFuture<T>) recoverRepository((RecoverRepositoryCommand) command);
        }

        throw new UnsupportedOperationException(command.toString());
    }

    // Project operations

    private CompletableFuture<Void> createProject(CreateProjectCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            final WrappedDekDetails wdekDetails = c.wdekDetails();
            final boolean encrypt = wdekDetails != null;
            if (encrypt) {
                encryptionStorageManager.storeWdek(wdekDetails);
            }

            try {
                projectManager.create(c.projectName(), c.timestamp(), c.author(), encrypt);
            } catch (Throwable t) {
                if (encrypt) {
                    try {
                        encryptionStorageManager.removeWdek(c.projectName(), Project.REPO_DOGMA,
                                                            c.wdekDetails().dekVersion(), true);
                    } catch (Throwable t2) {
                        logger.warn("Failed to remove the WDEK of {}/{}",
                                    c.projectName(), Project.REPO_DOGMA, t2);
                    }
                }
                Exceptions.throwUnsafely(t);
            }

            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Void> removeProject(RemoveProjectCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.remove(c.projectName());
            return null;
        }, repositoryWorker).thenRun(repoStatusManager::refreshReadOnlyMetrics);
    }

    private CompletableFuture<Void> unremoveProject(UnremoveProjectCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.unremove(c.projectName());
            return null;
        }, repositoryWorker).thenRun(repoStatusManager::refreshReadOnlyMetrics);
    }

    private CompletableFuture<Void> purgeProject(PurgeProjectCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.markForPurge(c.projectName());
            return null;
        }, repositoryWorker).thenCompose(unused -> {
            if (projectManager.exists(c.projectName())) {
                // markForPurge() was a no-op because the project was not in the removed state;
                // keep its status so read-only enforcement is not silently defeated.
                return CompletableFuture.<Void>completedFuture(null);
            }
            // Best-effort cleanup: a failure here must not fail the already-applied purge command.
            return repoStatusManager.removeProjectStatus(c.projectName(), c.author())
                                    .exceptionally(cause -> {
                                        logger.warn("Failed to remove the status of the purged project: {}",
                                                    c.projectName(), cause);
                                        return null;
                                    });
        });
    }

    // Repository operations

    private CompletableFuture<Void> createRepository(CreateRepositoryCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            final WrappedDekDetails wdekDetails = c.wdekDetails();
            final boolean encrypt = wdekDetails != null;
            if (encrypt) {
                encryptionStorageManager.storeWdek(wdekDetails);
            }

            try {
                projectManager.get(c.projectName()).repos().create(c.repositoryName(), c.timestamp(),
                                                                   c.author(), encrypt);
            } catch (Throwable t) {
                if (encrypt) {
                    try {
                        encryptionStorageManager.removeWdek(c.projectName(), c.repositoryName(),
                                                            c.wdekDetails().dekVersion(), true);
                    } catch (Throwable t2) {
                        logger.warn("Failed to remove the WDEK of {}/{}",
                                    c.projectName(), c.repositoryName(), t2);
                    }
                }
                Exceptions.throwUnsafely(t);
            }

            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Void> removeRepository(RemoveRepositoryCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.get(c.projectName()).repos().remove(c.repositoryName());
            return null;
        }, repositoryWorker).thenRun(repoStatusManager::refreshReadOnlyMetrics);
    }

    private CompletableFuture<Void> unremoveRepository(UnremoveRepositoryCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.get(c.projectName()).repos().unremove(c.repositoryName());
            return null;
        }, repositoryWorker).thenRun(repoStatusManager::refreshReadOnlyMetrics);
    }

    private CompletableFuture<Void> purgeRepository(PurgeRepositoryCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            projectManager.get(c.projectName()).repos().markForPurge(c.repositoryName());
            return null;
        }, repositoryWorker).thenCompose(unused -> {
            if (projectManager.exists(c.projectName()) &&
                projectManager.get(c.projectName()).repos().exists(c.repositoryName())) {
                // markForPurge() was a no-op because the repository was not in the removed state;
                // keep its status so read-only enforcement is not silently defeated.
                return CompletableFuture.<Void>completedFuture(null);
            }
            // Best-effort cleanup: a failure here must not fail the already-applied purge command.
            return repoStatusManager.removeRepoStatus(c.projectName(), c.repositoryName(), c.author())
                                    .exceptionally(cause -> {
                                        logger.warn("Failed to remove the status of the purged repository:" +
                                                    " {}/{}", c.projectName(), c.repositoryName(), cause);
                                        return null;
                                    });
        });
    }

    private CompletableFuture<Void> migrateToEncryptedRepository(MigrateToEncryptedRepositoryCommand c) {
        final RepositoryManager repositoryManager = projectManager.get(c.projectName()).repos();
        final Repository repository = repositoryManager.get(c.repositoryName());
        if (repository.isEncrypted()) {
            throw new IllegalStateException(
                    "The repository is already encrypted: " + c.projectName() + '/' + c.repositoryName());
        }
        return CompletableFuture.supplyAsync(() -> {
            encryptionStorageManager.storeWdek(c.wdekDetails());
            try {
                repositoryManager.migrateToEncryptedRepository(c.repositoryName());
            } catch (Throwable t) {
                try {
                    encryptionStorageManager.removeWdek(c.projectName(), c.repositoryName(),
                                                        c.wdekDetails().dekVersion(), true);
                } catch (Throwable t2) {
                    logger.warn("Failed to remove the WDEK of {}/{}",
                                c.projectName(), c.repositoryName(), t2);
                }
                throw t;
            }
            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Void> fallbackToFileRepository(FallbackToFileRepositoryCommand c) {
        final RepositoryManager repositoryManager = projectManager.get(c.projectName()).repos();
        final Repository repository = repositoryManager.get(c.repositoryName());
        if (!repository.isEncrypted()) {
            throw new IllegalStateException(
                    "The repository is not encrypted: " + c.projectName() + '/' + c.repositoryName());
        }
        return CompletableFuture.supplyAsync(() -> {
            repositoryManager.fallbackToFileRepository(c.repositoryName());
            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<CommitResult> push(RepositoryCommand<?> c, boolean normalizing) {
        if (c instanceof TransformCommand) {
            final TransformCommand transformCommand = (TransformCommand) c;
            return repo(c).commit(transformCommand.baseRevision(), transformCommand.timestamp(),
                                  transformCommand.author(), transformCommand.summary(),
                                  transformCommand.detail(), transformCommand.markup(),
                                  transformCommand.transformer());
        }
        assert c instanceof AbstractPushCommand;
        final AbstractPushCommand<?> pushCommand = (AbstractPushCommand<?>) c;
        return repo(c).commit(pushCommand.baseRevision(), pushCommand.timestamp(), pushCommand.author(),
                              pushCommand.summary(), pushCommand.detail(), pushCommand.markup(),
                              pushCommand.changes(), normalizing);
    }

    private Repository repo(RepositoryCommand<?> c) {
        return projectManager.get(c.projectName()).repos().get(c.repositoryName());
    }

    private CompletableFuture<Revision> recoverRepository(RecoverRepositoryCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            // Re-verify the read-only precondition at apply time: a command that made the repository
            // writable again (and any push after it) is ordered before this one in the replication log, so
            // proceeding would silently discard those commits. The check uses only the replicated
            // repository/project scope, so every replica decides identically; on the originating source it
            // fails before the command is stored, so the recovery aborts cleanly.
            if (!repoStatusManager.isRepoOrProjectReadOnly(c.projectName(), c.repositoryName())) {
                throw new IllegalStateException(
                        "cannot recover " + c.projectName() + '/' + c.repositoryName() +
                        "; the repository is no longer read-only. Make it read-only and retry.");
            }
            // The manager no-ops when the repository is already converged (the source or a healthy replica),
            // otherwise it resets to c.resetToRevision() and replays c.commits(). The result is always the
            // source head so every replica returns the same value (the replication log verifies equality).
            projectManager.get(c.projectName()).repos()
                          .recoverRepository(c.repositoryName(), c.resetToRevision(), c.commits());
            return c.headRevision();
        }, repositoryWorker);
    }

    private CompletableFuture<Void> rewrapAllKeys() {
        if (!encryptionStorageManager.enabled()) {
            throw new IllegalStateException("Encryption is not enabled.");
        }

        logger.info("Rewrapping all keys with kek: {}", encryptionStorageManager.kekId());
        return CompletableFuture.supplyAsync(() -> encryptionStorageManager.rewrapAllKeys(repositoryWorker),
                                             repositoryWorker)
                                .thenCompose(Function.identity())
                                .handle((unused, cause) -> {
                                    if (cause != null) {
                                        logger.warn("Failed to rewrap all keys with kek: {}",
                                                    encryptionStorageManager.kekId(),
                                                    cause);
                                        Exceptions.throwUnsafely(cause);
                                    } else {
                                        logger.info("All keys rewrapped.");
                                    }
                                    return null;
                                });
    }

    private CompletableFuture<Void> rotateWdek(RotateWdekCommand c) {
        if (!encryptionStorageManager.enabled()) {
            throw new IllegalStateException("Encryption is not enabled. command: " + c);
        }

        return CompletableFuture.supplyAsync(() -> {
            logger.info("Rotating WDEK for {}/{} to version {}",
                        c.projectName(), c.repositoryName(), c.wdekDetails().dekVersion());
            try {
                encryptionStorageManager.rotateWdek(c.wdekDetails());
                logger.info("WDEK rotated for {}/{}.", c.projectName(), c.repositoryName());
            } catch (Throwable t) {
                logger.warn("Failed to rotate WDEK for {}/{} to version {}",
                            c.projectName(), c.repositoryName(), c.wdekDetails().dekVersion(), t);
                Exceptions.throwUnsafely(t);
            }
            if (c.reencrypt()) {
                try {
                    logger.info("Re-encrypting all data in {}/{} with the new WDEK.",
                                c.projectName(), c.repositoryName());
                    encryptionStorageManager.reencryptRepositoryData(c.projectName(), c.repositoryName());
                    logger.info("All data re-encrypted in {}/{}.", c.projectName(), c.repositoryName());
                } catch (Throwable t) {
                    logger.warn("Failed to re-encrypt all data in {}/{} with the new WDEK.",
                                c.projectName(), c.repositoryName(), t);
                    Exceptions.throwUnsafely(t);
                }
            }

            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Void> rotateSessionMasterKey(RotateSessionMasterKeyCommand c) {
        if (!encryptionStorageManager.encryptSessionCookie()) {
            throw new IllegalStateException("Session cookie encryption is not enabled. command: " + c);
        }

        return CompletableFuture.supplyAsync(() -> {
            final int version = c.sessionMasterKey().version();
            logger.info("Rotating session master key to version {}", version);
            try {
                encryptionStorageManager.rotateSessionMasterKey(c.sessionMasterKey());
                logger.info("Session master key rotated.");
            } catch (Throwable t) {
                logger.warn("Failed to rotate session master key to version {}",
                            version, t);
                Exceptions.throwUnsafely(t);
            }
            return null;
        }, repositoryWorker);
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

    private CompletableFuture<Void> createSessionMasterKey(CreateSessionMasterKeyCommand c) {
        if (!encryptionStorageManager.enabled()) {
            throw new IllegalStateException("Encryption is not enabled. command: " + c);
        }

        return CompletableFuture.supplyAsync(() -> {
            encryptionStorageManager.storeSessionMasterKey(c.sessionMasterKey());
            return null;
        }, repositoryWorker);
    }

    private CompletableFuture<Void> updateServerStatus(UpdateServerStatusCommand c) {
        return CompletableFuture.supplyAsync(() -> {
            serverStatusManager.updateStatus(c.serverStatus());
            statusManager().updateStatus(c);
            return null;
        }, serverStatusManager.sequentialExecutor());
    }

    private CompletableFuture<Void> updateProjectStatus(UpdateProjectStatusCommand c) {
        return repoStatusManager.updateProjectStatus(c.projectName(), c.author(), c.projectStatus());
    }

    private CompletableFuture<Void> updateRepositoryStatus(UpdateRepositoryStatusCommand c) {
        return repoStatusManager.updateRepoStatus(c.projectName(), c.repoName(), c.author(), c.repoStatus());
    }
}
