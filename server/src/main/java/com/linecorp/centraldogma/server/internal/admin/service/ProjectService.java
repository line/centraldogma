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

package com.linecorp.centraldogma.server.internal.admin.service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.admin.authentication.AuthenticationUtil;
import com.linecorp.centraldogma.server.internal.admin.dto.ProjectDto;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.httpapi.AbstractService;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;

/**
 * Annotated service object for managing projects.
 */
public class ProjectService extends AbstractService {

    private final MetadataService mds;

    public ProjectService(ProjectManager projectManager, CommandExecutor executor) {
        super(projectManager, executor);
        mds = new MetadataService(projectManager, executor);
        mds.initialize();
    }

    /**
     * GET /projects
     * Returns the list of the projects.
     */
    @Get("/projects")
    public CompletionStage<List<ProjectDto>> listProjects() {
        return CompletableFuture.supplyAsync(
                () -> projectManager().list().values().stream()
                                      .map(DtoConverter::convert)
                                      .collect(Collectors.toList()));
    }

    /**
     * POST /projects
     * Create a new project.
     */
    @Post("/projects")
    public CompletionStage<ProjectDto> createProject(AggregatedHttpMessage message) throws IOException {
        final Author author = AuthenticationUtil.currentAuthor();
        final ProjectDto dto = Jackson.readValue(message.content().toStringAscii(), ProjectDto.class);
        return execute(Command.createProject(author, dto.getName())).thenApply(unused -> dto);
    }

    // TODO(hyangtack) Test APIs to manipulate metadata.
    //                 These test APIs would be merged into existing HTTP APIs.
    /*
    @Get("/mds/projects")
    public CompletionStage<List<ProjectInfo>> listProjectInfo() {
        return mds.findProjects(AuthenticationUtil.currentUser());
    }

    @Post("/mds/projects/{projectName}")
    public CompletionStage<ProjectInfo> createProjectInfo(@Param("projectName") String projectName) {
        return mds.createProject(projectName, AuthenticationUtil.currentAuthor());
    }

    @Delete("/mds/projects/{projectName}")
    public CompletionStage<ProjectInfo> removeProjectInfo(@Param("projectName") String projectName) {
        return mds.removeProject(projectName, AuthenticationUtil.currentAuthor());
    }

    @Post("/mds/projects/{projectName}/repos/{repoName}")
    public CompletionStage<ProjectInfo> addRepo(@Param("projectName") String projectName,
                                                @Param("repoName") String repoName) {
        return mds.addRepo(projectName, AuthenticationUtil.currentAuthor(), repoName);
    }

    @Delete("/mds/projects/{projectName}/repos/{repoName}")
    public CompletionStage<ProjectInfo> removeRepo(@Param("projectName") String projectName,
                                                   @Param("repoName") String repoName) {
        return mds.removeRepo(projectName, AuthenticationUtil.currentAuthor(), repoName);
    }

    @Post("/mds/projects/{projectName}/members/{user}")
    public CompletionStage<ProjectInfo> addMember(@Param("projectName") String projectName,
                                                  @Param("user") String user,
                                                  @Param("role") Optional<String> role) {
        return mds.addMember(projectName, AuthenticationUtil.currentAuthor(), new User(user),
                             role.map(ProjectRole::of).orElse(ProjectRole.MEMBER));
    }

    @Delete("/mds/projects/{projectName}/members/{user}")
    public CompletionStage<ProjectInfo> removeMember(@Param("projectName") String projectName,
                                                     @Param("user") String user) {
        return mds.removeMember(projectName, AuthenticationUtil.currentAuthor(), new User(user));
    }

    @Patch("/mds/projects/{projectName}/members/{user}")
    public CompletionStage<ProjectInfo> changeRole(@Param("projectName") String projectName,
                                                   @Param("user") String user,
                                                   @Param("role") String role) {
        return mds.changeRole(projectName, AuthenticationUtil.currentAuthor(), new User(user),
                              ProjectRole.of(role));
    }
    */
}
