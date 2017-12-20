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
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.internal.api.v1.CreateProjectRequest;
import com.linecorp.centraldogma.internal.api.v1.ProjectDto;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.admin.model.ProjectInfo;
import com.linecorp.centraldogma.server.internal.admin.model.ProjectRole;
import com.linecorp.centraldogma.server.internal.admin.service.MetadataService;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
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
    public CompletionStage<List<ProjectDto>> listProjects(@Param("status") Optional<String> status) {
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
    public CompletionStage<ProjectDto> createProject(@RequestObject CreateProjectRequest request,
                                                     @RequestObject Author author) {
        return mds.createProject(request.name(), author, request.owners(), request.members())
                  .thenCompose(projectInfo -> execute(Command.createProject(author, projectInfo.name())))
                  .handle((unused, thrown) -> {
                      try {
                          if (thrown == null) {
                              return DtoConverter.convert(projectManager().get(request.name()));
                          } else {
                              return Exceptions.throwUnsafely(thrown);
                          }
                      } finally {
                          if (thrown != null) {
                              // Remove created project from metadata.
                              mds.removeProject(request.name(), author);
                          }
                      }
                  });
    }

    /**
     * DELETE /projects/{projectName}
     *
     * <p>Removes a project.
     */
    @Delete("/projects/{projectName}")
    public CompletableFuture<Void> removeProject(@RequestObject Project project,
                                                 @RequestObject Author author) {
        return execute(Command.removeProject(author, project.name()))
                .thenCompose(unused -> mds.removeProject(project.name(), author))
                .handle(HttpApiUtil::throwUnsafelyIfNonNull);
    }

    /**
     * PATCH /projects/{projectName}
     *
     * <p>Patches a project with the JSON_PATCH. Currently, only unremove project operation is supported.
     */
    @ConsumeType("application/json-patch+json")
    @Patch("/projects/{projectName}")
    public CompletionStage<ProjectDto> patchProject(@Param("projectName") String projectName,
                                                    @RequestObject JsonNode node,
                                                    @RequestObject Author author) {
        checkUnremoveArgument(node);
        return mds.unremoveProject(projectName, author)
                  .thenCompose(projectInfo -> execute(Command.unremoveProject(author, projectName)))
                  .handle(returnOrThrow(() -> DtoConverter.convert(projectManager().get(projectName))));
    }

    // TODO(hyangtack) The following APIs would return ProjectInfo to the client. It is different action
    //                 from the above API which is returning ProjectDto. We would handle this later.

    /**
     * POST /projects/{projectName}/members/{user}
     *
     * <p>Adds a user as a member of the project.
     */
    @Post("/projects/{projectName}/members/{user}")
    public CompletionStage<ProjectInfo> addMember(@Param("projectName") String projectName,
                                                  @Param("user") String user,
                                                  @Param("role") Optional<String> role,
                                                  @RequestObject Author author) {
        return mds.addMember(projectName, author, new User(user),
                             role.map(ProjectRole::of).orElse(ProjectRole.MEMBER));
    }

    /**
     * DELETE /projects/{projectName}/members/{user}
     *
     * <p>Remove a user from the member of the project.
     */
    @Delete("/projects/{projectName}/members/{user}")
    public CompletionStage<ProjectInfo> removeMember(@Param("projectName") String projectName,
                                                     @Param("user") String user,
                                                     @RequestObject Author author) {
        return mds.removeMember(projectName, author, new User(user));
    }

    /**
     * PATCH /projects/{projectName}/members/{user}?role={role}
     *
     * <p>Changes the role of a user.
     */
    @ConsumeType("application/json-patch+json")
    @Patch("/projects/{projectName}/members/{user}")
    public CompletionStage<ProjectInfo> changeMemberRole(@Param("projectName") String projectName,
                                                         @Param("user") String user,
                                                         @Param("role") String role,
                                                         @RequestObject Author author) {
        return mds.changeMemberRole(projectName, author, new User(user),
                                    ProjectRole.of(role));
    }

    /**
     * POST /projects/{projectName}/tokens/{appId}
     *
     * <p>Adds a token to the project.
     */
    @Post("/projects/{projectName}/tokens/{appId}")
    public CompletionStage<ProjectInfo> addToken(@Param("projectName") String projectName,
                                                 @Param("appId") String appId,
                                                 @Param("role") Optional<String> role,
                                                 @RequestObject Author author) {
        return mds.addToken(projectName, author, appId,
                            role.map(ProjectRole::of).orElse(ProjectRole.MEMBER));
    }

    /**
     * DELETE /projects/{projectName}/tokens/{appId}
     *
     * <p>Remove a token from the project.
     */
    @Delete("/projects/{projectName}/tokens/{appId}")
    public CompletionStage<ProjectInfo> removeToken(@Param("projectName") String projectName,
                                                    @Param("appId") String appId,
                                                    @RequestObject Author author) {
        return mds.removeToken(projectName, author, appId);
    }

    /**
     * PATCH /projects/{projectName}/members/{user}?role={role}
     *
     * <p>Changes the role of a user.
     */
    @ConsumeType("application/json-patch+json")
    @Patch("/projects/{projectName}/tokens/{appId}")
    public CompletionStage<ProjectInfo> changeTokenRole(@Param("projectName") String projectName,
                                                        @Param("appId") String appId,
                                                        @Param("role") String role,
                                                        @RequestObject Author author) {
        return mds.changeTokenRole(projectName, author, appId, ProjectRole.of(role));
    }
}
