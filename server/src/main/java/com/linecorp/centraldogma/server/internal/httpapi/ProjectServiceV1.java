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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static com.linecorp.centraldogma.server.internal.httpapi.HttpApiV1Util.newHttpResponseException;
import static com.linecorp.centraldogma.server.internal.httpapi.HttpApiV1Util.unremovePatch;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Throwables;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.annotation.ConsumeType;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.centraldogma.internal.httpapi.v1.ProjectDto;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.server.internal.admin.authentication.AuthenticationUtil;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.StorageExistsException;
import com.linecorp.centraldogma.server.internal.storage.StorageNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;

/**
 * Annotated service object for managing projects.
 */
@ExceptionHandler(BadRequestHandler.class)
public class ProjectServiceV1 extends AbstractService {

    public ProjectServiceV1(ProjectManager projectManager, CommandExecutor executor) {
        super(projectManager, executor);
    }

    /**
     * GET /projects?status={status}
     *
     * <p>Returns the list of projects or removed projects.
     */
    @Get("/projects")
    public CompletionStage<List<ProjectDto>> listProjects(@Param("status") Optional<String> status) {
        if (status.isPresent()) {
            if (!status.get().equalsIgnoreCase("removed")) {
                throw newHttpResponseException(HttpStatus.BAD_REQUEST,
                                               "invalid status: " + status + " (expected: removed)");
            }

            return CompletableFuture.supplyAsync(
                    () -> projectManager().listRemoved().stream()
                                          .map(ProjectDto::new)
                                          .collect(toImmutableList()));
        }

        return CompletableFuture.supplyAsync(() -> projectManager().list().values().stream()
                                                                   .map(DtoConverter::convert)
                                                                   .collect(toImmutableList()));
    }

    /**
     * POST /projects
     *
     * <p>Creates a new project.
     */
    @Post("/projects")
    public CompletionStage<ProjectDto> createProject(@RequestObject JsonNode node) {
        checkArgument(node.get("name") != null, "project name should be non-null");
        final String name = node.get("name").textValue();
        checkArgument(!isNullOrEmpty(name), "project name should be non-null");

        return execute(Command.createProject(AuthenticationUtil.currentAuthor(), name))
                .handle((unused, thrown) -> {
                    if (thrown != null) {
                        if (Throwables.getRootCause(thrown) instanceof StorageExistsException) {
                            throw newHttpResponseException(HttpStatus.CONFLICT,
                                                           "project " + name + " already exists");
                        }
                        return Exceptions.throwUnsafely(thrown);
                    }
                    return DtoConverter.convert(projectManager().get(name));
                });
    }

    /**
     * DELETE /projects/{projectName}
     *
     * <p>Removes a project.
     */
    @Delete("/projects/{projectName}")
    public CompletableFuture<Void> removeProject(@Param("projectName") String name) {
        return execute(Command.removeProject(AuthenticationUtil.currentAuthor(), name))
                .handle(voidFunction((unused, thrown) -> {
                    if (thrown != null) {
                        if (Throwables.getRootCause(thrown) instanceof StorageNotFoundException) {
                            throw newHttpResponseException(HttpStatus.NOT_FOUND,
                                                           "project " + name + " not found");
                        }
                        Exceptions.throwUnsafely(thrown);
                    }
                }));
    }

    /**
     * PATCH /projects/{projectName}
     *
     * <p>Patches a project with the JSON_PATCH. Currently, only unremove project operation is supported.
     */
    @ConsumeType("application/json-patch+json")
    @Patch("/projects/{projectName}")
    public CompletionStage<ProjectDto> patchProject(@Param("projectName") String name,
                                                    @RequestObject JsonNode node) {
        try {
            // validate if it is a JSON patch
            JsonPatch.fromJson(node);
        } catch (IOException e) {
            throw newHttpResponseException(HttpStatus.BAD_REQUEST,
                                           "not a valid JSON patch: " + node.toString() +
                                           " (expected: " + unremovePatch.toString() + ')');
        }

        // check if the patch is same with the unremovePatch
        if (!node.equals(unremovePatch)) {
            throw newHttpResponseException(HttpStatus.CONFLICT,
                                           "not supported JSON patch: " + node.toString() +
                                           " (expected: " + unremovePatch.toString() + ')');
        }

        //  if it's same, do the operation
        return execute(Command.unremoveProject(AuthenticationUtil.currentAuthor(), name))
                .handle((project, thrown) -> {
                    if (thrown != null) {
                        if (Throwables.getRootCause(thrown) instanceof StorageNotFoundException) {
                            throw newHttpResponseException(HttpStatus.NOT_FOUND,
                                                           "project " + name + " not found");
                        }
                        return Exceptions.throwUnsafely(thrown);
                    }
                    return DtoConverter.convert(projectManager().get(name));
                });
    }
}
