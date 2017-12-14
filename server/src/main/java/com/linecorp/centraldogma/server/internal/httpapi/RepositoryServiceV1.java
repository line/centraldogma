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

package com.linecorp.centraldogma.server.internal.httpapi;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static com.linecorp.centraldogma.server.internal.httpapi.HttpApiV1Util.getJsonNode;
import static com.linecorp.centraldogma.server.internal.httpapi.HttpApiV1Util.newHttpResponseException;
import static com.linecorp.centraldogma.server.internal.httpapi.HttpApiV1Util.unremovePatch;
import static java.util.stream.Collectors.toList;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Throwables;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.annotation.ConsumeType;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.centraldogma.internal.httpapi.v1.RepositoryDto;
import com.linecorp.centraldogma.server.internal.admin.authentication.AuthenticationUtil;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.StorageExistsException;
import com.linecorp.centraldogma.server.internal.storage.StorageNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * Annotated service object for managing repositories.
 */
@ExceptionHandler(BadRequestHandler.class)
public class RepositoryServiceV1 extends AbstractService {

    public RepositoryServiceV1(ProjectManager projectManager, CommandExecutor executor) {
        super(projectManager, executor);
    }

    /**
     * GET /projects/{projectName}/repos?status={status}
     *
     * <p>Returns the list of the repositories or removed repositories.
     */
    @Get("/projects/{projectName}/repos")
    public CompletionStage<List<RepositoryDto>> listRepositories(@Param("projectName") String projectName,
                                                                 @Param("status") Optional<String> status) {
        checkProjectExists(projectName);
        if (status.isPresent()) {
            if (!status.get().equalsIgnoreCase("removed")) {
                throw newHttpResponseException(HttpStatus.BAD_REQUEST, "invalid status: " + status);
            }

            return CompletableFuture.completedFuture(
                    projectManager().get(projectName).repos().listRemoved().stream()
                                    .map(RepositoryDto::new).collect(toList()));
        }

        return CompletableFuture.completedFuture(projectManager().get(projectName).repos().list().values()
                                                                 .stream().map(DtoConverter::convert)
                                                                 .collect(toList()));
    }

    /**
     * POST /projects/{projectName}/repos
     *
     * <p>Creates a new repository.
     */
    @Post("/projects/{projectName}/repos")
    public CompletionStage<RepositoryDto> createRepository(@Param("projectName") String projectName,
                                                           AggregatedHttpMessage message) {
        checkProjectExists(projectName);

        final String repoName = getName(message);
        return execute(Command.createRepository(AuthenticationUtil.currentAuthor(), projectName, repoName))
                .handle((unused, thrown) -> {
                    if (thrown != null) {
                        if (Throwables.getRootCause(thrown) instanceof StorageExistsException) {
                            throw newHttpResponseException(HttpStatus.BAD_REQUEST,
                                                           "repository " + repoName + " already exists");
                        }
                        return Exceptions.throwUnsafely(thrown);
                    }
                    return DtoConverter.convert(getRepository(projectName, repoName));
                });
    }

    private static String getName(AggregatedHttpMessage message) {
        final JsonNode jsonNode = getJsonNode(message);
        checkArgument(jsonNode.get("name") != null, "repository name should be non-null");
        final String repoName = jsonNode.get("name").textValue();
        checkArgument(!isNullOrEmpty(repoName), "repository name should be non-null");
        return repoName;
    }

    /**
     * DELETE /projects/{projectName}/repos/{repoName}
     *
     * <p>Removes a repository.
     */
    @Delete("/projects/{projectName}/repos/{repoName}")
    public CompletionStage<Void> removeRepository(@Param("projectName") String projectName,
                                                  @Param("repoName") String repoName) {
        checkProjectExists(projectName);
        return execute(Command.removeRepository(AuthenticationUtil.currentAuthor(), projectName, repoName))
                .handle(voidFunction((unused, thrown) -> {
                    if (thrown != null) {
                        if (Throwables.getRootCause(thrown) instanceof StorageNotFoundException) {
                            throw newHttpResponseException(HttpStatus.NOT_FOUND,
                                                           "repository " + repoName + " not found");
                        }
                        Exceptions.throwUnsafely(thrown);
                    }
                }));
    }

    /**
     * PATCH /projects/{projectName}/repos/{repoName}
     *
     * <p>Patches a repository with the JSON_PATCH. Currently, only unremove repository operation is supported.
     */
    @ConsumeType("application/json-patch+json")
    @Patch("/projects/{projectName}/repos/{repoName}")
    public CompletionStage<RepositoryDto> patchRepository(@Param("projectName") String projectName,
                                                          @Param("repoName") String repoName,
                                                          AggregatedHttpMessage message) {
        checkProjectExists(projectName);
        final JsonNode jsonNode = getJsonNode(message);

        if (!unremovePatch.equals(jsonNode)) {
            throw newHttpResponseException(HttpStatus.BAD_REQUEST,
                                           "not supported JSON patch: " + message.content() +
                                           " (expected: " + unremovePatch.toString() + ')');
        }

        return execute(Command.unremoveRepository(AuthenticationUtil.currentAuthor(), projectName, repoName))
                .handle((unused, thrown) -> {
                    if (thrown != null) {
                        if (Throwables.getRootCause(thrown) instanceof StorageNotFoundException) {
                            throw newHttpResponseException(HttpStatus.NOT_FOUND,
                                                           "repository " + repoName + " not found");
                        }
                        return Exceptions.throwUnsafely(thrown);
                    }
                    return DtoConverter.convert(getRepository(projectName, repoName));
                });
    }

    private Repository getRepository(String projectName, String repoName) {
        return projectManager().get(projectName).repos().get(repoName);
    }

    private void checkProjectExists(String projectName) {
        if (!projectManager().exists(projectName)) {
            throw newHttpResponseException(HttpStatus.NOT_FOUND, "project " + projectName + " not found");
        }
    }
}
