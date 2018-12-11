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
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.checkStatusArgument;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.checkUnremoveArgument;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.returnOrThrow;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.internal.api.v1.CreateProjectRequest;
import com.linecorp.centraldogma.internal.api.v1.ProjectDto;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresAdministrator;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresRole;
import com.linecorp.centraldogma.server.internal.api.converter.CreateApiResponseConverter;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.metadata.MetadataService;
import com.linecorp.centraldogma.server.internal.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.internal.metadata.ProjectRole;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;

/**
 * Annotated service object for managing projects.
 */
@ExceptionHandler(HttpApiExceptionHandler.class)
public class ProjectServiceV1 extends AbstractService {

    private final MetadataService mds;

    public ProjectServiceV1(ProjectManager projectManager, CommandExecutor executor, MetadataService mds) {
        super(projectManager, executor);
        this.mds = requireNonNull(mds, "mds");
    }

    /**
     * GET /projects?status={status}
     *
     * <p>Returns the list of projects or removed projects.
     */
    @Get("/projects")
    public CompletableFuture<List<ProjectDto>> listProjects(@Param("status") Optional<String> status) {
        if (status.isPresent()) {
            checkStatusArgument(status.get());
            return CompletableFuture.supplyAsync(() -> projectManager().listRemoved().stream()
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
    @ResponseConverter(CreateApiResponseConverter.class)
    public CompletableFuture<ProjectDto> createProject(CreateProjectRequest request, Author author) {
        return execute(Command.createProject(author, request.name()))
                .handle(returnOrThrow(() -> DtoConverter.convert(projectManager().get(request.name()))));
    }

    /**
     * GET /projects/{projectName}
     *
     * <p>Gets the {@link ProjectMetadata} of the specified {@code projectName}.
     * If a {@code checkPermissionOnly} parameter is {@code true}, it is returned whether the user has
     * permission to read the metadata of the specified {@code projectName}.
     */
    @Get("/projects/{projectName}")
    @RequiresRole(roles = { ProjectRole.OWNER, ProjectRole.MEMBER })
    public CompletableFuture<ProjectMetadata> getProjectMetadata(
            @Param("projectName") String projectName,
            @Param("checkPermissionOnly") Optional<Boolean> isCheckPermissionOnly) {
        if (isCheckPermissionOnly.orElse(false)) {
            return CompletableFuture.completedFuture(null);
        }
        return mds.getProject(projectName);
    }

    /**
     * DELETE /projects/{projectName}
     *
     * <p>Removes a project.
     */
    @Delete("/projects/{projectName}")
    @RequiresRole(roles = ProjectRole.OWNER)
    public CompletableFuture<Void> removeProject(Project project, Author author) {
        // Metadata must be updated first because it cannot be updated if the project is removed.
        return mds.removeProject(author, project.name())
                  .thenCompose(unused -> execute(Command.removeProject(author, project.name())))
                  .handle(HttpApiUtil::throwUnsafelyIfNonNull);
    }

    /**
     * PATCH /projects/{projectName}
     *
     * <p>Patches a project with the JSON_PATCH. Currently, only unremove project operation is supported.
     */
    @Consumes("application/json-patch+json")
    @Patch("/projects/{projectName}")
    @RequiresAdministrator
    public CompletableFuture<ProjectDto> patchProject(@Param("projectName") String projectName,
                                                      JsonNode node,
                                                      Author author) {
        checkUnremoveArgument(node);
        // Restore the project first then update its metadata as 'active'.
        return execute(Command.unremoveProject(author, projectName))
                .thenCompose(unused -> mds.restoreProject(author, projectName))
                .handle(returnOrThrow(() -> DtoConverter.convert(projectManager().get(projectName))));
    }
}
