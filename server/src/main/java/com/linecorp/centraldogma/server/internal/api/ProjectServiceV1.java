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
import static com.linecorp.centraldogma.server.internal.api.DtoConverter.newProjectDto;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.checkStatusArgument;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.checkUnremoveArgument;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.returnOrThrow;
import static com.linecorp.centraldogma.server.metadata.ProjectMetadata.DOGMA_PROJECT_METADATA;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.ContextAwareBlockingTaskExecutor;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
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
import com.linecorp.centraldogma.internal.api.v1.CreateProjectRequest;
import com.linecorp.centraldogma.internal.api.v1.ProjectDto;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresProjectRole;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresSystemAdministrator;
import com.linecorp.centraldogma.server.internal.api.converter.CreateApiResponseConverter;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectApiManager;
import com.linecorp.centraldogma.server.metadata.AppIdentityRegistration;
import com.linecorp.centraldogma.server.metadata.Member;
import com.linecorp.centraldogma.server.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.metadata.UserWithAppIdentity;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;

/**
 * Annotated service object for managing projects.
 */
@ProducesJson
public class ProjectServiceV1 extends AbstractService {

    private final ProjectApiManager projectApiManager;

    public ProjectServiceV1(ProjectApiManager projectApiManager, CommandExecutor executor) {
        super(executor);
        this.projectApiManager = requireNonNull(projectApiManager, "projectApiManager");
    }

    /**
     * GET /projects?status={status}
     *
     * <p>Returns the list of projects or removed projects.
     */
    @Get("/projects")
    public CompletableFuture<List<ProjectDto>> listProjects(@Param @Nullable String status, User user) {
        final ContextAwareBlockingTaskExecutor executor =
                ServiceRequestContext.current().blockingTaskExecutor();
        if (status != null) {
            checkStatusArgument(status);
            return CompletableFuture.supplyAsync(() -> projectApiManager.listRemovedProjects().keySet()
                                                                        .stream()
                                                                        .map(ProjectDto::new)
                                                                        .collect(toImmutableList()), executor);
        }

        return CompletableFuture.supplyAsync(() -> {
            return projectApiManager.listProjects(user).values().stream()
                                    .map(project -> newProjectDto(project, getUserRole(project, user)))
                                    .collect(toImmutableList());
        }, executor);
    }

    private static ProjectRole getUserRole(Project project, User user) {
        if (user.isSystemAdmin()) {
            return ProjectRole.OWNER;
        }

        final ProjectMetadata metadata = project.metadata();
        if (metadata == null) {
            // Metadata is null for the internal project which belongs to system administrators.
            return ProjectRole.GUEST;
        }

        ProjectRole role = null;
        if (user instanceof UserWithAppIdentity) {
            final String appId = user.login(); // login is appId for UserWithAppIdentity
            final AppIdentityRegistration appIdentityRegistration = metadata.appIds().get(appId);
            if (appIdentityRegistration != null) {
                role = appIdentityRegistration.role();
            }
        } else {
            final Member member = metadata.memberOrDefault(user.id(), null);
            if (member != null) {
                role = member.role();
            }
        }

        if (role == null) {
            role = ProjectRole.GUEST;
        }

        return role;
    }

    /**
     * POST /projects
     *
     * <p>Creates a new project.
     */
    @Post("/projects")
    @StatusCode(201)
    @ResponseConverter(CreateApiResponseConverter.class)
    public CompletableFuture<ProjectDto> createProject(CreateProjectRequest request, Author author, User user) {
        return projectApiManager.createProject(request.name(), author).handle(returnOrThrow(() -> {
            final Project project = projectApiManager.getProject(request.name(), user);
            return newProjectDto(project, ProjectRole.OWNER);
        }));
    }

    /**
     * GET /projects/{projectName}
     *
     * <p>Gets the {@link ProjectMetadata} of the specified {@code projectName}.
     */
    @Get("/projects/{projectName}")
    @RequiresProjectRole(ProjectRole.MEMBER)
    public CompletableFuture<ProjectMetadata> getProjectMetadata(@Param String projectName) {
        if (InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(projectName)) {
            return UnmodifiableFuture.completedFuture(DOGMA_PROJECT_METADATA);
        }
        // Remove the Dogma repository from the metadata to avoid exposing it to the user.
        return projectApiManager.getProjectMetadata(projectName)
                                .thenApply(ProjectMetadata::withoutDogmaRepo);
    }

    /**
     * DELETE /projects/{projectName}
     *
     * <p>Removes a project.
     */
    @Delete("/projects/{projectName}")
    @RequiresProjectRole(ProjectRole.OWNER)
    public CompletableFuture<Void> removeProject(Project project, Author author) {
        return projectApiManager.removeProject(project.name(), author);
    }

    /**
     * DELETE /projects/{projectName}/removed
     *
     * <p>Purges a project that was removed before.
     */
    @Delete("/projects/{projectName}/removed")
    @RequiresProjectRole(ProjectRole.OWNER)
    public CompletableFuture<Void> purgeProject(@Param String projectName, Author author) {
        return projectApiManager.purgeProject(projectName, author);
    }

    // TODO(minwoox): Migrate to /projects/{projectName}:unremove when it's supported.

    /**
     * PATCH /projects/{projectName}
     *
     * <p>Patches a project with the JSON_PATCH. Currently, only unremove project operation is supported.
     */
    @Consumes("application/json-patch+json")
    @Patch("/projects/{projectName}")
    @RequiresSystemAdministrator
    public CompletableFuture<ProjectDto> patchProject(@Param String projectName, JsonNode node, Author author,
                                                      User user) {
        checkUnremoveArgument(node);
        return projectApiManager.unremoveProject(projectName, author)
                                .handle(returnOrThrow(() -> newProjectDto(
                                        projectApiManager.getProject(projectName, user), ProjectRole.OWNER)));
    }
}
