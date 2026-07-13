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

package com.linecorp.centraldogma.server.internal.api;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.server.internal.api.DtoConverter.newRepositoryDto;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.checkUnremoveArgument;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.ReplicationStatus;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.CreateRepositoryRequest;
import com.linecorp.centraldogma.internal.api.v1.RepositoryDto;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresProjectRole;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresRepositoryRole;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresSystemAdministrator;
import com.linecorp.centraldogma.server.internal.api.converter.CreateApiResponseConverter;
import com.linecorp.centraldogma.server.internal.management.RepoStatusManager;
import com.linecorp.centraldogma.server.internal.management.RepositoryState;
import com.linecorp.centraldogma.server.internal.replication.RecoveryPayloadBuilder;
import com.linecorp.centraldogma.server.internal.replication.ZooKeeperCommandExecutor;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.StorageException;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.encryption.WrappedDekDetails;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;

import io.micrometer.core.instrument.Tag;

/**
 * Annotated service object for managing repositories.
 */
@ProducesJson
public class RepositoryServiceV1 extends AbstractService {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryServiceV1.class);

    private final MetadataService mds;
    private final EncryptionStorageManager encryptionStorageManager;
    private final RepoStatusManager repoStatusManager;
    private final RecoveryPayloadBuilder recoveryPayloadBuilder;

    public RepositoryServiceV1(CommandExecutor executor, MetadataService mds,
                               EncryptionStorageManager encryptionStorageManager,
                               RepoStatusManager repoStatusManager,
                               RecoveryPayloadBuilder recoveryPayloadBuilder) {
        super(executor);
        this.repoStatusManager = repoStatusManager;
        this.mds = requireNonNull(mds, "mds");
        this.encryptionStorageManager = requireNonNull(encryptionStorageManager, "encryptionStorageManager");
        this.recoveryPayloadBuilder = requireNonNull(recoveryPayloadBuilder, "recoveryPayloadBuilder");
    }

    /**
     * GET /projects/{projectName}/repos?status={status}
     *
     * <p>Returns the list of the repositories or removed repositories.
     */
    @Get("/projects/{projectName}/repos")
    public CompletableFuture<List<RepositoryDto>> listRepositories(ServiceRequestContext ctx, Project project,
                                                                   @Param @Nullable String status, User user) {
        if (status != null) {
            HttpApiUtil.checkStatusArgument(status);
        }
        final boolean removedOnly = status != null;

        if (InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(project.name()) && !user.isSystemAdmin()) {
            return HttpApiUtil.throwResponse(
                    ctx, HttpStatus.FORBIDDEN,
                    "You must be a system administrator to retrieve repositories of the dogma project.");
        }

        if (!removedOnly) {
            return CompletableFuture.completedFuture(
                    project.repos().list().values().stream()
                           .filter(r -> user.isSystemAdmin() || !Project.isInternalRepo(r.name()))
                           .map(repository -> {
                               final RepositoryState repositoryState =
                                       repoStatusManager.getRepoStatus(project.name(), repository.name());
                               return newRepositoryDto(repository, repositoryState.status());
                           })
                           .collect(toImmutableList()));
        }

        // Return removed repositories only if the user has the owner role.
        return mds.findProjectRole(project.name(), user).handle((role, throwable) -> {
            final boolean hasOwnerRole = role == ProjectRole.OWNER;
            if (hasOwnerRole) {
                return removedRepositories(project);
            }
            return HttpApiUtil.throwResponse(
                    ctx, HttpStatus.FORBIDDEN,
                    "You must be an owner of project '%s' to retrieve removed repositories.",
                    project.name());
        });
    }

    private ReplicationStatus getReplicationStatus(Repository repository) {
        return getReplicationStatus(repository.parent().name(), repository.name());
    }

    private ReplicationStatus getReplicationStatus(String projectName, String repoName) {
        if (repoName.equals(Project.REPO_META)) {
            repoName = Project.REPO_DOGMA;
        }
        return repoStatusManager.getRepoStatus(projectName, repoName).status();
    }

    private static ImmutableList<RepositoryDto> removedRepositories(Project project) {
        return project.repos().listRemoved().keySet().stream().map(RepositoryDto::removed)
                      .collect(toImmutableList());
    }

    /**
     * POST /projects/{projectName}/repos
     *
     * <p>Creates a new repository.
     */
    @Post("/projects/{projectName}/repos")
    @StatusCode(201)
    @ResponseConverter(CreateApiResponseConverter.class)
    @RequiresProjectRole(ProjectRole.MEMBER)
    public CompletableFuture<RepositoryDto> createRepository(ServiceRequestContext ctx, Project project,
                                                             CreateRepositoryRequest request,
                                                             Author author) {
        final String repoName = request.name();
        if (Project.isInternalRepo(repoName)) {
            return HttpApiUtil.throwResponse(ctx, HttpStatus.FORBIDDEN,
                                             "An internal repository cannot be created.");
        }

        if (request.encrypt() && !encryptionStorageManager.enabled()) {
            return HttpApiUtil.throwResponse(ctx, HttpStatus.BAD_REQUEST,
                                             "Encryption is not enabled in the server.");
        }

        final boolean encrypt = request.encrypt() || isEncryptedProject(project);

        final CommandExecutor commandExecutor = executor();
        final CompletableFuture<Revision> future =
                RepositoryServiceUtil.createRepository(commandExecutor, mds, author, project.name(), repoName,
                                                       encrypt, encryptionStorageManager);
        return future.thenApply(unused -> {
            final Repository repository = project.repos().get(repoName);
            return newRepositoryDto(repository, getReplicationStatus(repository));
        });
    }

    /**
     * DELETE /projects/{projectName}/repos/{repoName}
     *
     * <p>Removes a repository.
     */
    @Delete("/projects/{projectName}/repos/{repoName}")
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    public CompletableFuture<Void> removeRepository(ServiceRequestContext ctx,
                                                    @Param String repoName,
                                                    Repository repository,
                                                    Author author) {
        if (Project.isInternalRepo(repoName)) {
            return HttpApiUtil.throwResponse(ctx, HttpStatus.FORBIDDEN,
                                             "An internal repository cannot be removed.");
        }
        return RepositoryServiceUtil.removeRepository(executor(), mds, author,
                                                      repository.parent().name(), repoName)
                                    .handle(HttpApiUtil::throwUnsafelyIfNonNull);
    }

    /**
     * DELETE /projects/{projectName}/repos/{repoName}/removed
     *
     * <p>Purges a repository that was removed before.
     */
    @Delete("/projects/{projectName}/repos/{repoName}/removed")
    @RequiresProjectRole(ProjectRole.OWNER)
    public CompletableFuture<Void> purgeRepository(@Param String repoName,
                                                   Project project, Author author) {
        return execute(Command.purgeRepository(author, project.name(), repoName))
                .thenCompose(unused -> mds.purgeRepo(author, project.name(), repoName)
                                          .handle(HttpApiUtil::throwUnsafelyIfNonNull));
    }

    // TODO(minwoox): Migrate to /projects/{projectName}/repos/{repoName}:unremove when it's supported.

    /**
     * PATCH /projects/{projectName}/repos/{repoName}
     *
     * <p>Patches a repository with the JSON_PATCH. Currently, only unremove repository operation is supported.
     */
    @Consumes("application/json-patch+json")
    @Patch("/projects/{projectName}/repos/{repoName}")
    @RequiresProjectRole(ProjectRole.OWNER)
    public CompletableFuture<RepositoryDto> patchRepository(@Param String repoName,
                                                            Project project,
                                                            JsonNode node,
                                                            Author author) {
        checkUnremoveArgument(node);
        return execute(Command.unremoveRepository(author, project.name(), repoName))
                .thenCompose(unused -> mds.restoreRepo(author, project.name(), repoName))
                .thenApply(unused -> {
                    final Repository repository = project.repos().get(repoName);
                    return newRepositoryDto(repository, getReplicationStatus(repository));
                });
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/revision/{revision}
     *
     * <p>Normalizes the revision into an absolute revision.
     */
    @Get("/projects/{projectName}/repos/{repoName}/revision/{revision}")
    @RequiresRepositoryRole(RepositoryRole.READ)
    public Map<String, Integer> normalizeRevision(ServiceRequestContext ctx,
                                                  Repository repository, @Param String revision) {
        final Revision normalizedRevision = repository.normalizeNow(new Revision(revision));
        final Revision head = repository.normalizeNow(Revision.HEAD);
        increaseCounterIfOldRevisionUsed(ctx, repository, normalizedRevision, head);
        return ImmutableMap.of("revision", normalizedRevision.major());
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/status
     *
     * <p>Returns the repository status.
     */
    @Get("/projects/{projectName}/repos/{repoName}")
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    public RepositoryDto status(Project project, Repository repository) {
        rejectIfDogmaProject(project);
        return newRepositoryDto(repository, getReplicationStatus(repository));
    }

    /**
     * PUT /projects/{projectName}/repos/{repoName}/status
     *
     * <p>Changes the repository status.
     */
    @Put("/projects/{projectName}/repos/{repoName}/status")
    @Consumes("application/json")
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    public CompletableFuture<RepositoryDto> updateStatus(Project project,
                                                         Repository repository,
                                                         Author author,
                                                         UpdateRepositoryStatusRequest statusRequest) {
        rejectIfDogmaProject(project);
        final ReplicationStatus newStatus = statusRequest.status();
        return updateRepositoryStatus(author, project, repository, newStatus)
                .thenApply(state -> newRepositoryDto(repository, newStatus));
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/head
     *
     * <p>Returns the head of the repository <em>on the replica that served the request</em>, identified by
     * both its revision and its commit ID. Two replicas of the same repository always share a revision,
     * even when their histories have diverged, so only the commit ID proves that they hold the same
     * history. It is how an administrator confirms that a recovery converged before making the repository
     * writable again.
     */
    @Get("/projects/{projectName}/repos/{repoName}/head")
    @RequiresSystemAdministrator
    public RepositoryHead head(Repository repository) {
        final Revision headRevision = repository.normalizeNow(Revision.HEAD);
        final ObjectId commitId;
        try {
            commitId = repository.jGitRepository().resolve(Constants.R_HEADS + Constants.MASTER);
        } catch (IOException e) {
            throw new StorageException("failed to resolve the head commit of " +
                                       repository.parent().name() + '/' + repository.name(), e);
        }
        if (commitId == null) {
            throw new StorageException("no head commit in " +
                                       repository.parent().name() + '/' + repository.name());
        }
        return new RepositoryHead(headRevision, commitId.name());
    }

    /**
     * POST /projects/{projectName}/repos/{repoName}/recover
     *
     * <p>Recovers the repository from a diverged state, using the repository of the replica whose server ID
     * is {@code sourceServerId} as the single source of truth: every other replica resets its repository to
     * just before {@code fromRevision} and replays the source's commits up to the source's head, so all
     * replicas converge to identical commit IDs.
     *
     * <p>Replicated (ZooKeeper) mode only. The repository must be read-only before recovery so that no new
     * commit can be originated while the recovery is in flight (the precondition is re-verified when the
     * command is applied), and it stays read-only afterwards. Note that a force-push races recovery
     * deliberately — it bypasses read-only, so a force-pushed commit that lands between the payload build
     * and the apply is discarded by the replay, on the source replica too.
     *
     * <p>Neither result means the cluster has converged: the replicas other than the source apply the
     * recovery when they replay it from the replication log. {@code COMPLETED} means the source replica
     * originated the recovery; {@code REQUESTED} means the source replica was asked to originate it over
     * the replication log, best-effort — a failure is only reported in the source replica's log. The
     * administrator confirms convergence with {@code GET .../head} on every replica before making the
     * repository writable again. Recovery should not run during a rolling upgrade: a replica that does not
     * know the recovery commands yet skips them and turns read-only.
     */
    @Post("/projects/{projectName}/repos/{repoName}/recover")
    @Consumes("application/json")
    @RequiresSystemAdministrator
    public CompletableFuture<RecoverRepositoryResponse> recover(ServiceRequestContext ctx,
                                                                Project project,
                                                                Repository repository,
                                                                Author author,
                                                                RecoverRepositoryRequest request) {
        final ZooKeeperCommandExecutor zkExecutor = validateRecoveryPrerequisites(ctx, project, repository,
                                                                                  request);
        final String projectName = project.name();
        final String repoName = repository.name();
        final int sourceServerId = request.sourceServerId();
        final Revision fromRevision = new Revision(request.fromRevision());
        ctx.setRequestTimeoutMillis(Long.MAX_VALUE); // Disable the request timeout for recovery.

        if (zkExecutor.replicaId() == sourceServerId) {
            // This replica is the source of truth; build the payload from the local storage and originate
            // the recovery command directly.
            logger.info("Originating a recovery of {}/{} from revision {} as the source replica.",
                        projectName, repoName, fromRevision);
            return CompletableFuture
                    .supplyAsync(() -> recoveryPayloadBuilder.build(author, projectName, repoName,
                                                                    sourceServerId, fromRevision),
                                 ctx.blockingTaskExecutor())
                    .thenCompose(this::execute)
                    .thenApply(headRevision -> new RecoverRepositoryResponse(
                            RecoveryStatus.COMPLETED, headRevision.major()));
        }

        // Ask the source replica to originate the recovery via the replication log.
        logger.info("Requesting a recovery of {}/{} from revision {} to the source replica {}.",
                    projectName, repoName, fromRevision, sourceServerId);
        return execute(Command.recoverRepositoryRequest(author, projectName, repoName, sourceServerId,
                                                        fromRevision))
                .thenApply(unused -> new RecoverRepositoryResponse(RecoveryStatus.REQUESTED, null));
    }

    private ZooKeeperCommandExecutor validateRecoveryPrerequisites(ServiceRequestContext ctx, Project project,
                                                                   Repository repository,
                                                                   RecoverRepositoryRequest request) {
        if (InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(project.name()) ||
            Project.isInternalRepo(repository.name())) {
            // Internal repository content is written by content transformers without text normalization,
            // so a replay cannot reproduce it byte-identically.
            return HttpApiUtil.throwResponse(
                    ctx, HttpStatus.FORBIDDEN,
                    "Cannot recover an internal repository: %s/%s", project.name(), repository.name());
        }
        if (!(executor() instanceof ZooKeeperCommandExecutor)) {
            throw new IllegalArgumentException(
                    "Repository recovery is only supported in replicated (ZooKeeper) mode.");
        }
        if (repository.isEncrypted()) {
            throw new IllegalArgumentException(
                    "Recovery is not supported for an encrypted repository: " +
                    project.name() + '/' + repository.name());
        }
        final ZooKeeperCommandExecutor zkExecutor = (ZooKeeperCommandExecutor) executor();
        if (!zkExecutor.replicationConfig().servers().containsKey(request.sourceServerId())) {
            throw new IllegalArgumentException(
                    "sourceServerId: " + request.sourceServerId() + " (expected: one of " +
                    zkExecutor.replicationConfig().servers().keySet() + ')');
        }
        if (getReplicationStatus(repository) != ReplicationStatus.READ_ONLY) {
            return HttpApiUtil.throwResponse(
                    ctx, HttpStatus.CONFLICT,
                    "The repository must be read-only before recovery so that no new commit can be " +
                    "originated while the recovery is in flight: %s/%s. Change the status to READ_ONLY " +
                    "first.", project.name(), repository.name());
        }
        return zkExecutor;
    }

    /**
     * POST /projects/{projectName}/repos/{repoName}/migrate/file
     *
     * <p>Falls back the repository from an encrypted repository to a file-based repository.
     */
    @Post("/projects/{projectName}/repos/{repoName}/migrate/file")
    @RequiresSystemAdministrator
    public CompletableFuture<RepositoryDto> fallbackToFileRepository(ServiceRequestContext ctx,
                                                                     Project project,
                                                                     Repository repository,
                                                                     Author author) {
        validateFallbackPrerequisites(ctx, project, repository);
        ctx.setRequestTimeoutMillis(Long.MAX_VALUE); // Disable the request timeout for migration.

        final boolean isDogmaProject =
                InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(project.name());
        if (isDogmaProject) {
            return fallback(author, project, repository, true).toCompletableFuture();
        }

        final ReplicationStatus currentStatus = getReplicationStatus(repository);
        if (currentStatus == ReplicationStatus.READ_ONLY) {
            // Already read-only, skip the redundant status change.
            return fallback(author, project, repository, false).toCompletableFuture();
        }
        return updateRepositoryStatus(author, project, repository, ReplicationStatus.READ_ONLY)
                .thenCompose(unused -> fallback(author, project, repository, false));
    }

    private void validateFallbackPrerequisites(ServiceRequestContext ctx, Project project,
                                               Repository repository) {
        if (InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(project.name()) &&
            !Project.REPO_DOGMA.equals(repository.name())) {
            throw new IllegalArgumentException(
                    "Only the dogma repository can be fallen back in the dogma project." +
                    " repository: " + repository.name());
        }

        if (!repository.isEncrypted()) {
            throw new IllegalArgumentException(
                    "The repository is not encrypted. Cannot fallback to a file-based repository." +
                    " project: " + project.name() + ", repository: " + repository.name());
        }

        // Allow fallback even when the repository is in read-only mode.
    }

    private CompletionStage<RepositoryDto> fallback(Author author, Project project,
                                                    Repository repository,
                                                    boolean skipStatusChange) {
        final String projectName = project.name();
        final String repoName = repository.name();
        logger.info("Starting repository fallback to file-based: project={}, repository={}",
                    projectName, repoName);

        final Command<Void> command = Command.fallbackToFileRepository(
                null, author, projectName, repoName);

        return executor().execute(command)
                         .handle((unused, cause) -> {
                             if (cause != null) {
                                 logger.warn("failed to fallback repository to a file-based repository: " +
                                             "project={}, repository={}", projectName, repoName, cause);
                                 if (skipStatusChange) {
                                     return UnmodifiableFuture
                                             .<RepositoryDto>exceptionallyCompletedFuture(cause);
                                 }
                                 return updateRepositoryStatus(author, project, repository,
                                                               ReplicationStatus.WRITABLE)
                                         .thenApply(unused1 ->
                                                            Exceptions.<RepositoryDto>throwUnsafely(cause));
                             }
                             logger.info("Successfully fallback repository to a file-based repository: " +
                                         "project={}, repository={}", projectName, repoName);
                             if (skipStatusChange) {
                                 final Repository updatedRepository =
                                         project.repos().get(repository.name());
                                 return UnmodifiableFuture.completedFuture(
                                         newRepositoryDto(updatedRepository, ReplicationStatus.WRITABLE));
                             }
                             return updateRepositoryStatus(author, project, repository,
                                                           ReplicationStatus.WRITABLE)
                                     .thenApply(unused1 -> {
                                         final Repository updatedRepository =
                                                 project.repos().get(repository.name());
                                         return newRepositoryDto(updatedRepository, ReplicationStatus.WRITABLE);
                                     });
                         }).thenCompose(Function.identity());
    }

    /**
     * POST /projects/{projectName}/repos/{repoName}/migrate/encrypted
     *
     * <p>Migrates the repository to an encrypted repository.
     */
    @Post("/projects/{projectName}/repos/{repoName}/migrate/encrypted")
    @RequiresSystemAdministrator
    public CompletableFuture<RepositoryDto> migrateToEncryptedRepository(ServiceRequestContext ctx,
                                                                         Project project,
                                                                         Repository repository,
                                                                         Author author) {
        validateMigrationPrerequisites(ctx, project, repository);
        ctx.setRequestTimeoutMillis(Long.MAX_VALUE); // Disable the request timeout for migration.

        final boolean isDogmaProject =
                InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(project.name());

        return encryptionStorageManager
                .generateWdek()
                .thenCompose(wdek -> {
                    final WrappedDekDetails wdekDetails = new WrappedDekDetails(
                            wdek, 1, encryptionStorageManager.kekId(),
                            project.name(), repository.name());
                    if (isDogmaProject) {
                        // The dogma project does not have project metadata, so the repository
                        // status cannot be changed. Migrate directly without changing the status.
                        return migrate(author, project, repository, wdekDetails, true);
                    }
                    return updateRepositoryStatus(author, project, repository, ReplicationStatus.READ_ONLY)
                            .thenCompose(unused -> migrate(author, project, repository,
                                                           wdekDetails, false));
                });
    }

    private void validateMigrationPrerequisites(ServiceRequestContext ctx, Project project,
                                                Repository repository) {
        if (!encryptionStorageManager.enabled()) {
            throw new IllegalArgumentException(
                    "Encryption is not enabled in the server. Cannot migrate to an encrypted repository.");
        }

        if (InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(project.name()) &&
            !Project.REPO_DOGMA.equals(repository.name())) {
            throw new IllegalArgumentException(
                    "Only the dogma repository can be migrated in the dogma project." +
                    " repository: " + repository.name());
        }

        if (repository.isEncrypted()) {
            throw new IllegalArgumentException(
                    "The repository is already encrypted. Cannot migrate to an encrypted repository again." +
                    " project: " + project.name() + ", repository: " + repository.name());
        }

        final Revision normalizeNow = repository.normalizeNow(Revision.HEAD);
        if (normalizeNow.major() > 2000) {
            // Prohibit migration to an encrypted repository if the repository has more than 2000 revisions.
            // After we implement the repository history rollover feature,
            // the repository can be migrated to an encrypted repository.
            throw new IllegalArgumentException(
                    "Cannot migrate a repository with more than 1000 revisions to an encrypted repository.");
        }

        // The dogma project does not have project metadata, so skip the status check.
        if (!InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(project.name())) {
            final ReplicationStatus currentStatus = getReplicationStatus(repository);
            if (currentStatus == ReplicationStatus.READ_ONLY) {
                HttpApiUtil.throwResponse(
                        ctx, HttpStatus.CONFLICT,
                        "Cannot migrate a read-only repository to an encrypted repository. " +
                        "Please change the status to WRITABLE first.");
            }
        }
    }

    private CompletableFuture<Void> updateRepositoryStatus(Author author, Project project,
                                                           Repository repository, ReplicationStatus newStatus) {
        final String projectName = project.name();
        final String repoName = repository.name();
        logger.info("Changing repository status: project={}, repository={}, status={}",
                    projectName, repoName, newStatus);
        final Command<Void> command = Command.updateRepositoryStatus(project.name(), repoName, author,
                                                                     newStatus);
        return executor().execute(command).handle((unused, cause) -> {
            if (cause != null) {
                logger.warn("Failed to change the repository status: project={}, repository={}, " +
                            "status={}", projectName, repoName, newStatus, cause);
                Exceptions.throwUnsafely(cause);
            } else {
                logger.info("Changed repository status: project={}, repository={}, status={}",
                            projectName, repoName, newStatus);
            }
            return null;
        });
    }

    private CompletionStage<RepositoryDto> migrate(Author author, Project project,
                                                   Repository repository,
                                                   WrappedDekDetails wdekDetails,
                                                   boolean skipStatusChange) {
        final String projectName = project.name();
        final String repoName = repository.name();
        logger.info("Starting repository encryption migration: project={}, repository={}",
                    projectName, repoName);

        final Command<Void> command = Command.migrateToEncryptedRepository(
                null, author, projectName, repoName, wdekDetails);

        return executor().execute(command).handle((unused, cause) -> {
            if (cause != null) {
                logger.warn("failed to migrate repository to an encrypted repository: " +
                            "project={}, repository={}", projectName, repoName, cause);
                if (skipStatusChange) {
                    return UnmodifiableFuture.<RepositoryDto>exceptionallyCompletedFuture(cause);
                }
                return updateRepositoryStatus(author, project, repository, ReplicationStatus.WRITABLE)
                        .thenApply(unused1 -> Exceptions.<RepositoryDto>throwUnsafely(cause));
            }
            logger.info("Successfully migrated repository to an encrypted repository: " +
                        "project={}, repository={}", projectName, repoName);
            if (skipStatusChange) {
                final Repository updatedRepository =
                        project.repos().get(repository.name());
                return UnmodifiableFuture.completedFuture(
                        newRepositoryDto(updatedRepository, ReplicationStatus.WRITABLE));
            }
            return updateRepositoryStatus(author, project, repository, ReplicationStatus.WRITABLE)
                    .thenApply(unused1 -> {
                        final Repository updatedRepository =
                                project.repos().get(repository.name());
                        return newRepositoryDto(updatedRepository, ReplicationStatus.WRITABLE);
                    });
        }).thenCompose(Function.identity());
    }

    static void increaseCounterIfOldRevisionUsed(ServiceRequestContext ctx, Repository repository,
                                                 Revision revision) {
        final Revision normalized = repository.normalizeNow(revision);
        final Revision head = repository.normalizeNow(Revision.HEAD);
        increaseCounterIfOldRevisionUsed(ctx, repository, normalized, head);
    }

    public static void increaseCounterIfOldRevisionUsed(
            ServiceRequestContext ctx, Repository repository, Revision normalized, Revision head) {
        final String projectName = repository.parent().name();
        final String repoName = repository.name();
        if (normalized.major() == 1) {
            ctx.log().whenRequestComplete().thenAccept(
                    log -> ctx.meterRegistry()
                              .counter("revisions.init", generateTags(projectName, repoName, log).build())
                              .increment());
        }
        if (head.major() - normalized.major() >= 5000) {
            ctx.log().whenRequestComplete().thenAccept(
                    log -> ctx.meterRegistry()
                              .summary("revisions.old",
                                       generateTags(projectName, repoName, log)
                                               .add(Tag.of("init", Boolean.toString(normalized.major() == 1)))
                                               .build())
                              .record(head.major() - normalized.major()));
        }
    }

    private static ImmutableList.Builder<Tag> generateTags(
            String projectName, String repoName, RequestOnlyLog log) {
        final ImmutableList.Builder<Tag> builder = ImmutableList.builder();
        return builder.add(Tag.of("project", projectName),
                           Tag.of("repo", repoName),
                           Tag.of("service", firstNonNull(log.serviceName(), "none")),
                           Tag.of("method", log.name()));
    }

    private static boolean isEncryptedProject(Project project) {
        return project.repos().get(Project.REPO_DOGMA).isEncrypted();
    }

    private static void rejectIfDogmaProject(Project project) {
        if (InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(project.name())) {
            throw new IllegalArgumentException(
                    "Cannot update the status of the internal project: " + project.name());
        }
    }
}
