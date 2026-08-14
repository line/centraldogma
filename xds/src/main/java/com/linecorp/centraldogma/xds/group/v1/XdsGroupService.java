/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.xds.group.v1;

import static com.linecorp.centraldogma.internal.Util.PROJECT_AND_REPO_NAME_PATTERN;
import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.currentUser;
import static com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil.getAuthor;
import static com.linecorp.centraldogma.server.internal.api.RepositoryServiceUtil.createRepository;
import static com.linecorp.centraldogma.server.internal.api.RepositoryServiceUtil.removeRepository;
import static com.linecorp.centraldogma.server.internal.storage.InternalProjectConstants.INTERNAL_PROJECT_XDS;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.errorResponse;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.project.Project;

/**
 * Annotated service object for managing xDS groups.
 */
public final class XdsGroupService {

    private final Project xdsProject;
    private final CommandExecutor commandExecutor;
    private final MetadataService mds;

    /**
     * Creates a new instance.
     */
    public XdsGroupService(Project xdsProject, CommandExecutor commandExecutor, MetadataService mds) {
        this.xdsProject = xdsProject;
        this.commandExecutor = commandExecutor;
        this.mds = mds;
    }

    /**
     * POST /xds/groups
     *
     * <p>Creates a new xDS group.
     */
    @Post("/xds/groups")
    public CompletableFuture<HttpResponse> createGroup(@Param("group_id") String groupId) {
        if (!PROJECT_AND_REPO_NAME_PATTERN.matcher(groupId).matches()) {
            return CompletableFuture.completedFuture(
                    errorResponse(HttpStatus.BAD_REQUEST, "Invalid group ID: " + groupId));
        }
        if (Project.isInternalRepo(groupId)) {
            return CompletableFuture.completedFuture(
                    errorResponse(HttpStatus.FORBIDDEN, "Cannot create internal repository: " + groupId));
        }
        if (xdsProject.repos().exists(groupId)) {
            return CompletableFuture.completedFuture(
                    errorResponse(HttpStatus.CONFLICT, "Group already exists: " + groupId));
        }
        final User createUser = currentUser();
        if (createUser == null) {
            return CompletableFuture.completedFuture(
                    errorResponse(HttpStatus.UNAUTHORIZED, "Authentication required"));
        }
        return createRepository(commandExecutor, mds, getAuthor(createUser), INTERNAL_PROJECT_XDS, groupId,
                                false, null)
                .handle((unused, cause) -> {
                    if (cause != null) {
                        final Throwable peeled = Exceptions.peel(cause);
                        if (peeled instanceof RepositoryExistsException) {
                            return errorResponse(HttpStatus.CONFLICT,
                                                 "Group already exists: " + groupId);
                        }
                        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, peeled);
                    }
                    return HttpResponse.of(HttpStatus.OK);
                });
    }

    /**
     * DELETE /xds/groups/{group_name}
     *
     * <p>Removes an xDS group.
     */
    @Delete("/xds/groups/{group_name}")
    public CompletableFuture<HttpResponse> deleteGroup(@Param("group_name") String groupName) {
        if (!xdsProject.repos().exists(groupName)) {
            return CompletableFuture.completedFuture(
                    errorResponse(HttpStatus.NOT_FOUND, "Group not found: " + groupName));
        }
        if (Project.isInternalRepo(groupName)) {
            return CompletableFuture.completedFuture(
                    errorResponse(HttpStatus.FORBIDDEN, "Cannot delete internal repository: " + groupName));
        }
        final User user = currentUser();
        if (user == null) {
            return CompletableFuture.completedFuture(
                    errorResponse(HttpStatus.UNAUTHORIZED, "Authentication required"));
        }
        final ProjectMetadata metadata = xdsProject.metadata();
        // @xds is not the internal dogma project, so metadata is always initialized — never null.
        assert metadata != null;
        final RepositoryRole role = MetadataService.findRepositoryRole(metadata, groupName, user);
        if (role != RepositoryRole.ADMIN) {
            return CompletableFuture.completedFuture(
                    errorResponse(HttpStatus.FORBIDDEN, "No admin permission for group: " + groupName));
        }
        return removeRepository(commandExecutor, mds, getAuthor(user), INTERNAL_PROJECT_XDS, groupName)
                .handle((unused, cause) -> {
                    if (cause != null) {
                        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                                             Exceptions.peel(cause));
                    }
                    return HttpResponse.of(HttpStatus.NO_CONTENT);
                });
    }
}
