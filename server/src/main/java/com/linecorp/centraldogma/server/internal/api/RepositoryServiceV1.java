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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.checkUnremoveArgument;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.returnOrThrow;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.annotation.ConsumeType;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.CreateRepositoryRequest;
import com.linecorp.centraldogma.internal.api.v1.RepositoryDto;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.api.auth.HasReadPermission;
import com.linecorp.centraldogma.server.internal.api.auth.ProjectOwnersOnly;
import com.linecorp.centraldogma.server.internal.api.converter.CreateApiResponseConverter;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.metadata.MetadataService;
import com.linecorp.centraldogma.server.internal.metadata.ProjectRole;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * Annotated service object for managing repositories.
 */
@ExceptionHandler(HttpApiExceptionHandler.class)
public class RepositoryServiceV1 extends AbstractService {

    private final MetadataService mds;

    public RepositoryServiceV1(ProjectManager projectManager, CommandExecutor executor,
                               MetadataService mds) {
        super(projectManager, executor);
        this.mds = requireNonNull(mds, "mds");
    }

    /**
     * GET /projects/{projectName}/repos?status={status}
     *
     * <p>Returns the list of the repositories or removed repositories.
     */
    @Get("/projects/{projectName}/repos")
    public CompletionStage<List<RepositoryDto>> listRepositories(@RequestObject Project project,
                                                                 @Param("status") Optional<String> status,
                                                                 @RequestObject User user) {
        status.ifPresent(HttpApiUtil::checkStatusArgument);
        return mds.findRole(project.name(), user).handle((role, throwable) -> {
            final boolean hasOwnerRole = role == ProjectRole.OWNER;
            if (status.isPresent()) {
                if (hasOwnerRole) {
                    return project.repos().listRemoved().stream().map(RepositoryDto::new)
                                  .collect(toImmutableList());
                }
                return Exceptions.throwUnsafely(HttpStatusException.of(HttpStatus.FORBIDDEN));
            }

            // Do not add "meta" repository to the list if the user is not an owner of the project.
            return project.repos().list().values().stream()
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
    @ResponseConverter(CreateApiResponseConverter.class)
    @Decorator(ProjectOwnersOnly.class)
    public CompletionStage<RepositoryDto> createRepository(@RequestObject Project project,
                                                           @RequestObject CreateRepositoryRequest request,
                                                           @RequestObject Author author) {
        return mds.addRepo(author, project.name(), request.name())
                  .thenCompose(p -> execute(Command.createRepository(author, project.name(), request.name())))
                  .handle((unused, cause) -> {
                      try {
                          if (cause == null) {
                              return DtoConverter.convert(project.repos().get(request.name()));
                          } else {
                              return Exceptions.throwUnsafely(cause);
                          }
                      } finally {
                          if (cause != null) {
                              // Remove created repository from metadata.
                              mds.removeRepo(author, project.name(), request.name());
                          }
                      }
                  });
    }

    /**
     * DELETE /projects/{projectName}/repos/{repoName}
     *
     * <p>Removes a repository.
     */
    @Delete("/projects/{projectName}/repos/{repoName}")
    @Decorator(ProjectOwnersOnly.class)
    public CompletionStage<Void> removeRepository(@RequestObject Repository repository,
                                                  @RequestObject Author author) {
        return execute(Command.removeRepository(author, repository.parent().name(), repository.name()))
                .thenCompose(unused -> mds.removeRepo(author, repository.parent().name(), repository.name()))
                .handle(HttpApiUtil::throwUnsafelyIfNonNull);
    }

    /**
     * PATCH /projects/{projectName}/repos/{repoName}
     *
     * <p>Patches a repository with the JSON_PATCH. Currently, only unremove repository operation is supported.
     */
    @ConsumeType("application/json-patch+json")
    @Patch("/projects/{projectName}/repos/{repoName}")
    @Decorator(ProjectOwnersOnly.class)
    public CompletionStage<RepositoryDto> patchRepository(@Param("repoName") String repoName,
                                                          @RequestObject Project project,
                                                          @RequestObject JsonNode node,
                                                          @RequestObject Author author) {
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
    @Decorator(HasReadPermission.class)
    public Map<String, Integer> normalizeRevision(@RequestObject Repository repository,
                                                  @Param("revision") String revision) {
        final Revision normalizedRevision = repository.normalizeNow(new Revision(revision));
        return ImmutableMap.of("revision", normalizedRevision.major());
    }
}
