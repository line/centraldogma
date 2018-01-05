/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.centraldogma.server.internal.api;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.checkStatusArgument;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.checkUnremoveArgument;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.returnOrThrow;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.annotation.ConsumeType;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.internal.api.v1.CreateRepositoryRequest;
import com.linecorp.centraldogma.internal.api.v1.RepositoryDto;
import com.linecorp.centraldogma.server.internal.admin.service.MetadataService;
import com.linecorp.centraldogma.server.internal.api.converter.CreateApiResponseConverter;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * Annotated service object for managing repositories.
 */
@ExceptionHandler(HttpApiExceptionHandler.class)
public class RepositoryServiceV1 extends AbstractService {

    private final MetadataService mds;

    public RepositoryServiceV1(ProjectManager projectManager, CommandExecutor executor, MetadataService mds) {
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
                                                                 @Param("status") Optional<String> status) {
        if (status.isPresent()) {
            checkStatusArgument(status.get());
            return CompletableFuture.completedFuture(project.repos().listRemoved().stream()
                                                            .map(RepositoryDto::new)
                                                            .collect(toImmutableList()));
        }

        return CompletableFuture.completedFuture(project.repos().list().values()
                                                        .stream().map(DtoConverter::convert)
                                                        .collect(toImmutableList()));
    }

    /**
     * POST /projects/{projectName}/repos
     *
     * <p>Creates a new repository.
     */
    @Post("/projects/{projectName}/repos")
    @ResponseConverter(CreateApiResponseConverter.class)
    public CompletionStage<RepositoryDto> createRepository(@RequestObject Project project,
                                                           @RequestObject CreateRepositoryRequest request,
                                                           @RequestObject Author author) {
        return mds.addRepo(project.name(), author, request.name())
                  .thenCompose(p -> execute(Command.createRepository(author, p.name(), request.name())))
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
                              mds.removeRepo(project.name(), author, request.name());
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
    public CompletionStage<Void> removeRepository(@RequestObject Repository repository,
                                                  @RequestObject Author author) {
        return execute(Command.removeRepository(author, repository.parent().name(), repository.name()))
                .thenCompose(unused -> mds.removeRepo(repository.parent().name(), author, repository.name()))
                .handle(HttpApiUtil::throwUnsafelyIfNonNull);
    }

    /**
     * PATCH /projects/{projectName}/repos/{repoName}
     *
     * <p>Patches a repository with the JSON_PATCH. Currently, only unremove repository operation is supported.
     */
    @ConsumeType("application/json-patch+json")
    @Patch("/projects/{projectName}/repos/{repoName}")
    public CompletionStage<RepositoryDto> patchRepository(@Param("repoName") String repoName,
                                                          @RequestObject Project project,
                                                          @RequestObject JsonNode node,
                                                          @RequestObject Author author) {
        checkUnremoveArgument(node);
        return mds.addRepo(project.name(), author, repoName)
                  .thenCompose(p -> execute(Command.unremoveRepository(author, project.name(), repoName)))
                  .handle(returnOrThrow(() -> DtoConverter.convert(project.repos().get(repoName))));
    }
}
