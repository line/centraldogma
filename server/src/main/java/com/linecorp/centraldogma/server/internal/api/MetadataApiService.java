/*
 * Copyright 2018 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.jsonpatch.JsonPatchOperation;
import com.linecorp.centraldogma.common.jsonpatch.ReplaceOperation;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresProjectRole;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresRepositoryRole;
import com.linecorp.centraldogma.server.metadata.Application;
import com.linecorp.centraldogma.server.metadata.ApplicationType;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.ProjectRoles;
import com.linecorp.centraldogma.server.metadata.Token;
import com.linecorp.centraldogma.server.metadata.User;

/**
 * Annotated service object for managing metadata of projects.
 */
@ProducesJson
public class MetadataApiService extends AbstractService {

    private final MetadataService mds;
    private final Function<String, String> loginNameNormalizer;

    public MetadataApiService(CommandExecutor executor, MetadataService mds,
                              Function<String, String> loginNameNormalizer) {
        super(executor);
        this.mds = requireNonNull(mds, "mds");
        this.loginNameNormalizer = requireNonNull(loginNameNormalizer, "loginNameNormalizer");
    }

    /**
     * POST /metadata/{projectName}/members
     *
     * <p>Adds a member to the specified {@code projectName}.
     */
    @RequiresProjectRole(ProjectRole.OWNER)
    @Post("/metadata/{projectName}/members")
    public CompletableFuture<Revision> addMember(@Param String projectName,
                                                 IdAndProjectRole request,
                                                 Author author) {
        final User member = new User(loginNameNormalizer.apply(request.id()));
        return mds.addMember(author, projectName, member, request.role());
    }

    /**
     * PATCH /metadata/{projectName}/members/{memberId}
     *
     * <p>Updates the {@link ProjectRole} of the specified {@code memberId} in the specified
     * {@code projectName}.
     */
    @RequiresProjectRole(ProjectRole.OWNER)
    @Patch("/metadata/{projectName}/members/{memberId}")
    @Consumes("application/json-patch+json")
    public CompletableFuture<Revision> updateMember(@Param String projectName,
                                                    @Param String memberId,
                                                    JsonPatch jsonPatch,
                                                    Author author) {
        final ReplaceOperation operation = ensureSingleReplaceOperation(jsonPatch, "/role");
        final ProjectRole role = ProjectRole.of(operation.value());
        final User member = new User(loginNameNormalizer.apply(memberId));
        return mds.getMember(projectName, member)
                  .thenCompose(unused -> mds.updateMemberRole(author, projectName, member, role));
    }

    /**
     * DELETE /metadata/{projectName}/members/{memberId}
     *
     * <p>Removes the specified {@code memberId} from the specified {@code projectName}.
     */
    @RequiresProjectRole(ProjectRole.OWNER)
    @Delete("/metadata/{projectName}/members/{memberId}")
    public CompletableFuture<Revision> removeMember(@Param String projectName,
                                                    @Param String memberId,
                                                    Author author) {
        final User member = new User(loginNameNormalizer.apply(memberId));
        return mds.getMember(projectName, member)
                  .thenCompose(unused -> mds.removeMember(author, projectName, member));
    }

    /**
     * POST /metadata/{projectName}/repos/{repoName}/roles/projects
     *
     * <p>Updates member and guest's {@link RepositoryRole}s of the specified {@code repoName} in the specified
     * {@code projectName}. The body of the request will be:
     * <pre>{@code
     * {
     *   "member": "WRITE",
     *   "guest": "READ"
     * }
     * }</pre>
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Post("/metadata/{projectName}/repos/{repoName}/roles/projects")
    public CompletableFuture<Revision> updateRepositoryProjectRoles(
            @Param String projectName,
            @Param String repoName,
            JsonNode payload,
            Author author) throws JsonProcessingException {
        final JsonNode guest = payload.get("guest");
        if (guest.isTextual()) {
            // TODO(ikhoon): Move this validation to the constructor of ProjectRoles once GUEST WRITE role is
            //               migrated to GUEST READ.
            final String role = guest.asText();
            if ("WRITE".equals(role)) {
                throw new IllegalArgumentException("WRITE is not allowed for GUEST");
            }
        }
        final ProjectRoles projectRoles = Jackson.treeToValue(payload, ProjectRoles.class);
        return mds.updateRepositoryProjectRoles(author, projectName, repoName, projectRoles);
    }

    /**
     * POST /metadata/{projectName}/repos/{repoName}/roles/users
     *
     * <p>Adds the {@link RepositoryRole} of the specific users to the specified {@code repoName} in the
     * specified {@code projectName}.
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Post("/metadata/{projectName}/repos/{repoName}/roles/users")
    public CompletableFuture<Revision> addUserRepositoryRole(
            @Param String projectName,
            @Param String repoName,
            IdAndRepositoryRole idAndRepositoryRole,
            Author author) {
        final User member = new User(loginNameNormalizer.apply(idAndRepositoryRole.id()));
        return mds.addUserRepositoryRole(author, projectName, repoName,
                                         member, idAndRepositoryRole.role());
    }

    /**
     * DELETE /metadata/{projectName}/repos/{repoName}/roles/users/{memberId}
     *
     * <p>Removes {@link RepositoryRole} of the specified {@code memberId} from the specified {@code repoName}
     * in the specified {@code projectName}.
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Delete("/metadata/{projectName}/repos/{repoName}/roles/users/{memberId}")
    public CompletableFuture<Revision> removeUserRepositoryRole(@Param String projectName,
                                                                @Param String repoName,
                                                                @Param String memberId,
                                                                Author author) {
        final User member = new User(loginNameNormalizer.apply(memberId));
        return mds.findRepositoryRole(projectName, repoName, member)
                  .thenCompose(unused -> mds.removeUserRepositoryRole(author, projectName,
                                                                      repoName, member));
    }

    /**
     * POST /metadata/{projectName}/applications
     *
     * <p>Adds an {@link Application} to the specified {@code projectName}.
     */
    @RequiresProjectRole(ProjectRole.OWNER)
    @Post("/metadata/{projectName}/applications")
    public CompletableFuture<Revision> addApplication(@Param String projectName,
                                                      IdAndProjectRole request,
                                                      Author author) {
        final Application application = mds.findApplicationByAppId(request.id());
        return mds.addApplication(author, projectName, application.appId(), request.role());
    }

    /**
     * PATCH /metadata/{projectName}/applications/{appId}
     *
     * <p>Updates the {@link ProjectRole} of the {@link Application} of the specified {@code appId}
     * in the specified {@code projectName}.
     */
    @RequiresProjectRole(ProjectRole.OWNER)
    @Patch("/metadata/{projectName}/applications/{appId}")
    @Consumes("application/json-patch+json")
    public CompletableFuture<Revision> updateApplicationRole(@Param String projectName,
                                                             @Param String appId,
                                                             JsonPatch jsonPatch,
                                                             Author author) {
        return updateApplicationRole(projectName, appId, jsonPatch, author, false);
    }

    private CompletableFuture<Revision> updateApplicationRole(
            String projectName, String appId,
            JsonPatch jsonPatch, Author author, boolean checkToken) {
        final ReplaceOperation operation = ensureSingleReplaceOperation(jsonPatch, "/role");
        final ProjectRole role = ProjectRole.of(operation.value());
        final Application application = mds.findApplicationByAppId(appId);
        if (checkToken && application.type() != ApplicationType.TOKEN) {
            throw new IllegalArgumentException("appId: " + appId + " is not a token");
        }
        return mds.updateApplicationRole(author, projectName, application, role);
    }

    /**
     * DELETE /metadata/{projectName}/applications/{appId}
     *
     * <p>Removes the {@link Application} of the specified {@code appId} from the specified {@code projectName}.
     */
    @RequiresProjectRole(ProjectRole.OWNER)
    @Delete("/metadata/{projectName}/applications/{appId}")
    public CompletableFuture<Revision> removeApplication(@Param String projectName,
                                                         @Param String appId,
                                                         Author author) {
        return removeApplication(projectName, appId, author, false);
    }

    private CompletableFuture<Revision> removeApplication(String projectName, String appId,
                                                          Author author, boolean checkToken) {
        final Application application = mds.findApplicationByAppId(appId);
        if (checkToken && application.type() != ApplicationType.TOKEN) {
            throw new IllegalArgumentException("appId: " + appId + " is not a token");
        }
        return mds.removeApplicationFromProject(author, projectName, application.appId());
    }

    /**
     * POST /metadata/{projectName}/repos/{repoName}/roles/applications
     *
     * <p>Adds the {@link RepositoryRole} for an application to the specified {@code repoName} in the specified
     * {@code projectName}.
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Post("/metadata/{projectName}/repos/{repoName}/roles/applications")
    public CompletableFuture<Revision> addApplicationRepositoryRole(
            @Param String projectName,
            @Param String repoName,
            IdAndRepositoryRole applicationAndRepositoryRole,
            Author author) {
        return mds.addApplicationRepositoryRole(author, projectName, repoName,
                                                applicationAndRepositoryRole.id(),
                                                applicationAndRepositoryRole.role());
    }

    /**
     * DELETE /metadata/{projectName}/repos/{repoName}/roles/applications/{appId}
     *
     * <p>Removes the {@link RepositoryRole} of the specified {@code appId} from the specified {@code repoName}
     * in the specified {@code projectName}.
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Delete("/metadata/{projectName}/repos/{repoName}/roles/applications/{appId}")
    public CompletableFuture<Revision> removeApplicationRepositoryRole(@Param String projectName,
                                                                       @Param String repoName,
                                                                       @Param String appId,
                                                                       Author author) {
        mds.findApplicationByAppId(appId); // Validate existence of the application.
        return mds.removeApplicationRepositoryRole(author, projectName, repoName, appId);
    }

    /**
     * POST /metadata/{projectName}/tokens
     *
     * <p>Adds a {@link Token} to the specified {@code projectName}.
     *
     * @deprecated Use {@link #addApplication(String, IdAndProjectRole, Author)} instead.
     */
    @RequiresProjectRole(ProjectRole.OWNER)
    @Post("/metadata/{projectName}/tokens")
    @Deprecated
    public CompletableFuture<Revision> addToken(@Param String projectName,
                                                IdAndProjectRole request,
                                                Author author) {
        final Application application = mds.findApplicationByAppId(request.id());
        if (application.type() != ApplicationType.TOKEN) {
            throw new IllegalArgumentException("appId: " + request.id() + " is not a token");
        }
        return mds.addApplication(author, projectName, application.appId(), request.role());
    }

    /**
     * PATCH /metadata/{projectName}/tokens/{appId}
     *
     * <p>Updates the {@link ProjectRole} of the {@link Token} of the specified {@code appId}
     * in the specified {@code projectName}.
     *
     * @deprecated Use {@link #updateApplicationRole(String, String, JsonPatch, Author)} instead.
     */
    @RequiresProjectRole(ProjectRole.OWNER)
    @Patch("/metadata/{projectName}/tokens/{appId}")
    @Consumes("application/json-patch+json")
    @Deprecated
    public CompletableFuture<Revision> updateTokenRole(@Param String projectName,
                                                       @Param String appId,
                                                       JsonPatch jsonPatch,
                                                       Author author) {
        return updateApplicationRole(projectName, appId, jsonPatch, author, true);
    }

    /**
     * DELETE /metadata/{projectName}/tokens/{appId}
     *
     * <p>Removes the {@link Token} of the specified {@code appId} from the specified {@code projectName}.
     *
     * @deprecated Use {@link #removeApplication(String, String, Author)} instead.
     */
    @RequiresProjectRole(ProjectRole.OWNER)
    @Delete("/metadata/{projectName}/tokens/{appId}")
    @Deprecated
    public CompletableFuture<Revision> removeToken(@Param String projectName,
                                                   @Param String appId,
                                                   Author author) {
        return removeApplication(projectName, appId, author, true);
    }

    /**
     * POST /metadata/{projectName}/repos/{repoName}/roles/tokens
     *
     * <p>Adds the {@link RepositoryRole} for a token to the specified {@code repoName} in the specified
     * {@code projectName}.
     *
     * @deprecated Use {@link #addApplicationRepositoryRole(String, String, IdAndRepositoryRole, Author)}
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Post("/metadata/{projectName}/repos/{repoName}/roles/tokens")
    @Deprecated
    public CompletableFuture<Revision> addTokenRepositoryRole(
            @Param String projectName,
            @Param String repoName,
            IdAndRepositoryRole tokenAndRepositoryRole,
            Author author) {
        final Application application = mds.findApplicationByAppId(tokenAndRepositoryRole.id());
        if (application.type() != ApplicationType.TOKEN) {
            throw new IllegalArgumentException("appId: " + tokenAndRepositoryRole.id() + " is not a token");
        }
        return mds.addApplicationRepositoryRole(author, projectName, repoName,
                                                tokenAndRepositoryRole.id(), tokenAndRepositoryRole.role());
    }

    /**
     * DELETE /metadata/{projectName}/repos/{repoName}/roles/tokens/{appId}
     *
     * <p>Removes the {@link RepositoryRole} of the specified {@code appId} from the specified {@code repoName}
     * in the specified {@code projectName}.
     *
     * @deprecated Use {@link #removeApplicationRepositoryRole(String, String, String, Author)}
     */
    @RequiresRepositoryRole(RepositoryRole.ADMIN)
    @Delete("/metadata/{projectName}/repos/{repoName}/roles/tokens/{appId}")
    @Deprecated
    public CompletableFuture<Revision> removeTokenRepositoryRole(@Param String projectName,
                                                                 @Param String repoName,
                                                                 @Param String appId,
                                                                 Author author) {
        final Application application = mds.findApplicationByAppId(appId);
        if (application.type() != ApplicationType.TOKEN) {
            throw new IllegalArgumentException("appId: " + appId + " is not a token");
        }
        return mds.removeApplicationRepositoryRole(author, projectName, repoName, appId);
    }

    private static ReplaceOperation ensureSingleReplaceOperation(JsonPatch patch, String expectedPath) {
        final List<JsonPatchOperation> operations = patch.operations();
        checkArgument(operations.size() == 1,
                      "Should be a single JSON patch operation in the list: " + operations.size());

        final JsonPatchOperation operation = patch.operations().get(0);
        checkArgument(operation instanceof ReplaceOperation,
                      "Should be a replace operation: " + operation);

        checkArgument(expectedPath.equals(operation.path().toString()),
                      "Invalid path value: " + operation.path());

        return (ReplaceOperation) operation;
    }

    public static final class IdAndProjectRole {

        private final String id;
        private final ProjectRole role;

        @VisibleForTesting
        @JsonCreator
        public IdAndProjectRole(@JsonProperty("id") String id,
                                @JsonProperty("role") ProjectRole role) {
            this.id = requireNonNull(id, "id");
            requireNonNull(role, "role");
            checkArgument(role == ProjectRole.OWNER || role == ProjectRole.MEMBER,
                          "Invalid role: " + role +
                          " (expected: '" + ProjectRole.OWNER + "' or '" + ProjectRole.MEMBER + "')");
            this.role = role;
        }

        @JsonProperty
        public String id() {
            return id;
        }

        @JsonProperty
        public ProjectRole role() {
            return role;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("id", id())
                              .add("role", role())
                              .toString();
        }
    }

    public static final class IdAndRepositoryRole {

        private final String id;
        private final RepositoryRole role;

        @VisibleForTesting
        @JsonCreator
        public IdAndRepositoryRole(@JsonProperty("id") String id,
                                   @JsonProperty("role") RepositoryRole role) {
            this.id = requireNonNull(id, "id");
            this.role = requireNonNull(role, "role");
        }

        @JsonProperty
        public String id() {
            return id;
        }

        @JsonProperty
        public RepositoryRole role() {
            return role;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("id", id())
                              .add("role", role())
                              .toString();
        }
    }
}
