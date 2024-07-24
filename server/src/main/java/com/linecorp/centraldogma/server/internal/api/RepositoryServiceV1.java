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

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.CreateRepositoryRequest;
import com.linecorp.centraldogma.internal.api.v1.RepositoryDto;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresReadPermission;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresRole;
import com.linecorp.centraldogma.server.internal.api.converter.CreateApiResponseConverter;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;

import io.micrometer.core.instrument.Tag;

/**
 * Annotated service object for managing repositories.
 */
@ProducesJson
public class RepositoryServiceV1 extends AbstractService {

    private final MetadataService mds;

    public RepositoryServiceV1(CommandExecutor executor, MetadataService mds) {
        super(executor);
        this.mds = requireNonNull(mds, "mds");
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

        return mds.findRole(project.name(), user).handle((role, throwable) -> {
            final boolean hasOwnerRole = role == ProjectRole.OWNER;
            if (status != null) {
                if (hasOwnerRole) {
                    return project.repos().listRemoved().keySet().stream().map(RepositoryDto::new)
                                  .collect(toImmutableList());
                }
                return HttpApiUtil.throwResponse(
                        ctx, HttpStatus.FORBIDDEN,
                        "You must be an owner of project '%s' to retrieve removed repositories.",
                        project.name());
            }

            return project.repos().list().values().stream()
                          .filter(r -> user.isAdmin() || !Project.REPO_DOGMA.equals(r.name()))
                          .filter(r -> hasOwnerRole || !Project.REPO_META.equals(r.name()))
                          .map(DtoConverter::convert)
                          .collect(toImmutableList());
        });
    }

    /**
     * POST /projects/{projectName}/repos
     *
     * <p>Creates a new repository.
     */
    @Post("/projects/{projectName}/repos")
    @StatusCode(201)
    @ResponseConverter(CreateApiResponseConverter.class)
    @RequiresRole(roles = ProjectRole.OWNER)
    public CompletableFuture<RepositoryDto> createRepository(ServiceRequestContext ctx, Project project,
                                                             CreateRepositoryRequest request,
                                                             Author author) {
        if (Project.isReservedRepoName(request.name())) {
            return HttpApiUtil.throwResponse(ctx, HttpStatus.FORBIDDEN,
                                             "A reserved repository cannot be created.");
        }
        return execute(Command.createRepository(author, project.name(), request.name()))
                .thenCompose(unused -> mds.addRepo(author, project.name(), request.name()))
                .handle(returnOrThrow(() -> DtoConverter.convert(project.repos().get(request.name()))));
    }

    /**
     * DELETE /projects/{projectName}/repos/{repoName}
     *
     * <p>Removes a repository.
     */
    @Delete("/projects/{projectName}/repos/{repoName}")
    @RequiresRole(roles = ProjectRole.OWNER)
    public CompletableFuture<Void> removeRepository(ServiceRequestContext ctx,
                                                    @Param String repoName,
                                                    Repository repository,
                                                    Author author) {
        if (Project.isReservedRepoName(repoName)) {
            return HttpApiUtil.throwResponse(ctx, HttpStatus.FORBIDDEN,
                                             "A reserved repository cannot be removed.");
        }
        return execute(Command.removeRepository(author, repository.parent().name(), repository.name()))
                .thenCompose(unused -> mds.removeRepo(author, repository.parent().name(), repository.name()))
                .handle(HttpApiUtil::throwUnsafelyIfNonNull);
    }

    /**
     * DELETE /projects/{projectName}/repos/{repoName}/removed
     *
     * <p>Purges a repository that was removed before.
     */
    @Delete("/projects/{projectName}/repos/{repoName}/removed")
    @RequiresRole(roles = ProjectRole.OWNER)
    public CompletableFuture<Void> purgeRepository(@Param String repoName,
                                                   Project project, Author author) {
        return execute(Command.purgeRepository(author, project.name(), repoName))
                .thenCompose(unused -> mds.purgeRepo(author, project.name(), repoName)
                                          .handle(HttpApiUtil::throwUnsafelyIfNonNull));
    }

    /**
     * PATCH /projects/{projectName}/repos/{repoName}
     *
     * <p>Patches a repository with the JSON_PATCH. Currently, only unremove repository operation is supported.
     */
    @Consumes("application/json-patch+json")
    @Patch("/projects/{projectName}/repos/{repoName}")
    @RequiresRole(roles = ProjectRole.OWNER)
    public CompletableFuture<RepositoryDto> patchRepository(@Param String repoName,
                                                            Project project,
                                                            JsonNode node,
                                                            Author author) {
        checkUnremoveArgument(node);
        return execute(Command.unremoveRepository(author, project.name(), repoName))
                .thenCompose(unused -> mds.restoreRepo(author, project.name(), repoName))
                .handle(returnOrThrow(() -> DtoConverter.convert(project.repos().get(repoName))));
    }

    /**
     * GET /projects/{projectName}/repos/{repoName}/revision/{revision}
     *
     * <p>Normalizes the revision into an absolute revision.
     */
    @Get("/projects/{projectName}/repos/{repoName}/revision/{revision}")
    @RequiresReadPermission
    public Map<String, Integer> normalizeRevision(ServiceRequestContext ctx,
                                                  Repository repository, @Param String revision) {
        final Revision normalizedRevision = repository.normalizeNow(new Revision(revision));
        final Revision head = repository.normalizeNow(Revision.HEAD);
        increaseCounterIfOldRevisionUsed(ctx, repository, normalizedRevision, head);
        return ImmutableMap.of("revision", normalizedRevision.major());
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
}
