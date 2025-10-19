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
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.checkUnremoveArgument;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.returnOrThrow;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.util.Exceptions;
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
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.RepositoryStatus;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.CreateRepositoryRequest;
import com.linecorp.centraldogma.internal.api.v1.RepositoryDto;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresProjectRole;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresRepositoryRole;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresSystemAdministrator;
import com.linecorp.centraldogma.server.internal.api.converter.CreateApiResponseConverter;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.metadata.RepositoryMetadata;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
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

    public RepositoryServiceV1(CommandExecutor executor, MetadataService mds,
                               EncryptionStorageManager encryptionStorageManager) {
        super(executor);
        this.mds = requireNonNull(mds, "mds");
        this.encryptionStorageManager = requireNonNull(encryptionStorageManager, "encryptionStorageManager");
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

        if (InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(project.name())) {
            if (user.isSystemAdmin()) {
                if (status != null) {
                    return CompletableFuture.completedFuture(removedRepositories(project));
                }
                return CompletableFuture.completedFuture(
                        project.repos().list().values().stream()
                               .map(repository -> DtoConverter.convert(repository, RepositoryStatus.ACTIVE))
                               .collect(toImmutableList()));
            }
            return HttpApiUtil.throwResponse(
                    ctx, HttpStatus.FORBIDDEN,
                    "You must be a system administrator to retrieve repositories of the dogma project.");
        }

        if (status == null) {
            final Map<String, RepositoryMetadata> repos = projectMetadata(project).repos();
            return CompletableFuture.completedFuture(
                    project.repos().list().values().stream()
                           .filter(r -> user.isSystemAdmin() || !Project.isInternalRepo(r.name()))
                           .map(repository -> DtoConverter.convert(repository, repos))
                           .collect(toImmutableList()));
        }

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

    private static ProjectMetadata projectMetadata(Project project) {
        assert !InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(project.name());
        final ProjectMetadata metadata = project.metadata();
        assert metadata != null; // not null because the project is not dogma project.
        return metadata;
    }

    private static RepositoryStatus repositoryStatus(Repository repository) {
        final Map<String, RepositoryMetadata> repos = projectMetadata(repository.parent()).repos();
        final String repoName = normalizeRepositoryName(repository);

        final RepositoryMetadata metadata = repos.get(repoName);
        if (metadata == null) {
            return RepositoryStatus.ACTIVE;
        } else {
            return metadata.status();
        }
    }

    private static String normalizeRepositoryName(Repository repository) {
        final String repoName = repository.name();
        if (!Project.REPO_META.equals(repoName)) {
            return repoName;
        }
        // Use dogma repository for the meta repository because the meta repository will be removed.
        return Project.REPO_DOGMA;
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

        final CommandExecutor commandExecutor = executor();
        final CompletableFuture<Revision> future =
                RepositoryServiceUtil.createRepository(commandExecutor, mds, author, project.name(), repoName,
                                                       request.encrypt(), encryptionStorageManager);
        return future.handle(returnOrThrow(() -> {
            final Repository repository = project.repos().get(repoName);
            return DtoConverter.convert(repository, repositoryStatus(repository));
        }));
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
                .handle(returnOrThrow(() -> {
                    final Repository repository = project.repos().get(repoName);
                    return DtoConverter.convert(repository, repositoryStatus(repository));
                }));
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
        return DtoConverter.convert(repository, repositoryStatus(repository));
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
        final RepositoryStatus oldStatus = repositoryStatus(repository);
        final RepositoryStatus newStatus = statusRequest.status();
        if (oldStatus == newStatus) {
            // No need to update the status, just return the current status.
            return CompletableFuture.completedFuture(DtoConverter.convert(repository, oldStatus));
        }

        return mds.updateRepositoryStatus(author, project.name(),
                                          normalizeRepositoryName(repository), newStatus)
                  .thenApply(unused -> DtoConverter.convert(repository, newStatus));
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

        return encryptionStorageManager
                .generateWdek()
                .thenCompose(wdek -> setRepositoryStatus(author, project, repository,
                                                         RepositoryStatus.READ_ONLY)
                        .thenCompose(unused -> migrate(author, project, repository, wdek)));
    }

    private void validateMigrationPrerequisites(ServiceRequestContext ctx, Project project,
                                                Repository repository) {
        if (!encryptionStorageManager.enabled()) {
            throw new IllegalArgumentException(
                    "Encryption is not enabled in the server. Cannot migrate to an encrypted repository.");
        }

        // TODO(minwoox): Dogma project and dogma repository will be migrated one day later by a plugin.
        if (InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(project.name()) ||
            project.name().startsWith("@") || Project.REPO_DOGMA.equals(repository.name())) {
            throw new IllegalArgumentException(
                    "Cannot migrate the internal project or repository to an encrypted repository. project: " +
                    project.name() + ", repository: " + repository.name());
        }

        final RepositoryStatus currentStatus = repositoryStatus(repository);
        if (repository.isEncrypted()) {
            throw new IllegalArgumentException(
                    "The repository is already encrypted. Cannot migrate to an encrypted repository again." +
                    " project: " + project.name() + ", repository: " + repository.name());
        }

        final Revision normalizeNow = repository.normalizeNow(Revision.HEAD);
        if (normalizeNow.major() > 1000) {
            // Prohibit migration to an encrypted repository if the repository has more than 1000 revisions.
            // After we implement the repository history rollover feature,
            // the repository can be migrated to an encrypted repository.
            throw new IllegalArgumentException(
                    "Cannot migrate a repository with more than 1000 revisions to an encrypted repository.");
        }

        if (currentStatus == RepositoryStatus.READ_ONLY) {
            HttpApiUtil.throwResponse(
                    ctx, HttpStatus.CONFLICT,
                    "Cannot migrate a read-only repository to an encrypted repository. " +
                    "Please change the status to ACTIVE first.");
        }
    }

    private CompletableFuture<Void> setRepositoryStatus(Author author, Project project, Repository repository,
                                                        RepositoryStatus status) {
        final String projectName = project.name();
        final String repoName = repository.name();
        logger.info("Changing repository status: project={}, repository={}, status={}",
                    projectName, repoName, status);
        return mds.updateRepositoryStatus(author, projectName, repoName, status)
                  .handle((unused, cause) -> {
                      if (cause != null) {
                          logger.warn("Failed to change the repository status: project={}, repository={}, " +
                                      "status={}", projectName, repoName, status, cause);
                          Exceptions.throwUnsafely(cause);
                      } else {
                          logger.info("Changed repository status: project={}, repository={}, status={}",
                                      projectName, repoName, status);
                      }
                      return null;
                  });
    }

    private CompletionStage<RepositoryDto> migrate(Author author, Project project,
                                                   Repository repository, byte[] wdek) {
        final String projectName = project.name();
        final String repoName = repository.name();
        logger.info("Starting repository encryption migration: project={}, repository={}",
                    projectName, repoName);

        final Command<Void> command = Command.migrateToEncryptedRepository(
                null, author, projectName, repoName, wdek);

        return executor().execute(command)
                         .handle((unused, cause) -> {
                             if (cause != null) {
                                 logger.warn("failed to migrate repository to an encrypted repository: " +
                                             "project={}, repository={}", projectName, repoName, cause);
                                 return setRepositoryStatus(author, project, repository,
                                                            RepositoryStatus.ACTIVE)
                                         .thenApply(unused1 -> (RepositoryDto) Exceptions.throwUnsafely(cause));
                             }
                             logger.info("Successfully migrated repository to an encrypted repository: " +
                                         "project={}, repository={}", projectName, repoName);
                             return setRepositoryStatus(author, project, repository, RepositoryStatus.ACTIVE)
                                     .thenApply(unused1 -> {
                                         final Repository updatedRepository =
                                                 project.repos().get(repository.name());
                                         return DtoConverter.convert(updatedRepository,
                                                                     RepositoryStatus.ACTIVE);
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

    private static void rejectIfDogmaProject(Project project) {
        if (InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(project.name())) {
            throw new IllegalArgumentException(
                    "Cannot update the status of the internal project: " + project.name());
        }
    }
}
