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

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.annotation.ConsumeType;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatchOperation;
import com.linecorp.centraldogma.internal.jsonpatch.ReplaceOperation;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.api.auth.ProjectOwnersOnly;
import com.linecorp.centraldogma.server.internal.metadata.MetadataService;
import com.linecorp.centraldogma.server.internal.metadata.PerRolePermissions;
import com.linecorp.centraldogma.server.internal.metadata.Permission;
import com.linecorp.centraldogma.server.internal.metadata.ProjectRole;
import com.linecorp.centraldogma.server.internal.metadata.Token;

/**
 * Annotated service object for managing metadata of projects.
 */
@ExceptionHandler(HttpApiExceptionHandler.class)
public class MetadataApiService {

    private static final TypeReference<Collection<Permission>> permissionsTypeRef =
            new TypeReference<Collection<Permission>>() {};

    private final MetadataService mds;

    public MetadataApiService(MetadataService mds) {
        this.mds = requireNonNull(mds, "mds");
    }

    /**
     * POST /metadata/{projectName}/members
     *
     * <p>Adds a member to the specified {@code projectName}.
     */
    @Post("/metadata/{projectName}/members")
    @Decorator(ProjectOwnersOnly.class)
    public CompletableFuture<Revision> addMember(@Param("projectName") String projectName,
                                                 @RequestObject IdentifierWithRole request,
                                                 @RequestObject Author author) {
        final ProjectRole role = toProjectRole(request.role());
        return mds.addMember(author, projectName, new User(request.id()), role);
    }

    /**
     * PATCH /metadata/{projectName}/members/{memberId}
     *
     * <p>Updates the {@link ProjectRole} of the specified {@code memberId} in the specified
     * {@code projectName}.
     */
    @Patch("/metadata/{projectName}/members/{memberId}")
    @ConsumeType("application/json-patch+json")
    @Decorator(ProjectOwnersOnly.class)
    public CompletableFuture<Revision> updateMember(@Param("projectName") String projectName,
                                                    @Param("memberId") String memberId,
                                                    @RequestObject JsonPatch jsonPatch,
                                                    @RequestObject Author author) {
        final ReplaceOperation operation = ensureSingleReplaceOperation(jsonPatch, "/role");
        final ProjectRole role = ProjectRole.of(operation.value());
        final User member = new User(urlDecode(memberId));
        return mds.getMember(projectName, member)
                  .thenCompose(unused -> mds.updateMemberRole(author, projectName, member, role));
    }

    /**
     * DELETE /metadata/{projectName}/members/{memberId}
     *
     * <p>Removes the specified {@code memberId} from the specified {@code projectName}.
     */
    @Delete("/metadata/{projectName}/members/{memberId}")
    @Decorator(ProjectOwnersOnly.class)
    public CompletableFuture<Revision> removeMember(@Param("projectName") String projectName,
                                                    @Param("memberId") String memberId,
                                                    @RequestObject Author author) {
        final User member = new User(urlDecode(memberId));
        return mds.getMember(projectName, member)
                  .thenCompose(unused -> mds.removeMember(author, projectName, member));
    }

    /**
     * POST /metadata/{projectName}/tokens
     *
     * <p>Adds a {@link Token} to the specified {@code projectName}.
     */
    @Post("/metadata/{projectName}/tokens")
    @Decorator(ProjectOwnersOnly.class)
    public CompletableFuture<Revision> addToken(@Param("projectName") String projectName,
                                                @RequestObject IdentifierWithRole request,
                                                @RequestObject Author author) {
        final ProjectRole role = toProjectRole(request.role());
        return mds.findTokenByAppId(request.id())
                  .thenCompose(token -> mds.addToken(author, projectName, token.appId(), role));
    }

    /**
     * PATCH /metadata/{projectName}/tokens/{appId}
     *
     * <p>Updates the {@link ProjectRole} of the {@link Token} of the specified {@code appId}
     * in the specified {@code projectName}.
     */
    @Patch("/metadata/{projectName}/tokens/{appId}")
    @ConsumeType("application/json-patch+json")
    @Decorator(ProjectOwnersOnly.class)
    public CompletableFuture<Revision> updateTokenRole(@Param("projectName") String projectName,
                                                       @Param("appId") String appId,
                                                       @RequestObject JsonPatch jsonPatch,
                                                       @RequestObject Author author) {
        final ReplaceOperation operation = ensureSingleReplaceOperation(jsonPatch, "/role");
        final ProjectRole role = ProjectRole.of(operation.value());
        return mds.findTokenByAppId(appId)
                  .thenCompose(token -> mds.updateTokenRole(author, projectName, token, role));
    }

    /**
     * DELETE /metadata/{projectName}/tokens/{appId}
     *
     * <p>Removes the {@link Token} of the specified {@code appId} from the specified {@code projectName}.
     */
    @Delete("/metadata/{projectName}/tokens/{appId}")
    @Decorator(ProjectOwnersOnly.class)
    public CompletableFuture<Revision> removeToken(@Param("projectName") String projectName,
                                                   @Param("appId") String appId,
                                                   @RequestObject Author author) {
        return mds.findTokenByAppId(appId)
                  .thenCompose(token -> mds.removeToken(author, projectName, token.appId()));
    }

    /**
     * POST /metadata/{projectName}/repos/{repoName}/perm/role
     *
     * <p>Updates the {@link PerRolePermissions} of the specified {@code repoName} in the specified
     * {@code projectName}.
     */
    @Post("/metadata/{projectName}/repos/{repoName}/perm/role")
    @Decorator(ProjectOwnersOnly.class)
    public CompletableFuture<Revision> updateRolePermission(@Param("projectName") String projectName,
                                                            @Param("repoName") String repoName,
                                                            @RequestObject PerRolePermissions newPermission,
                                                            @RequestObject Author author) {
        return mds.updatePerRolePermissions(author, projectName, repoName, newPermission);
    }

    /**
     * POST /metadata/{projectName}/repos/{repoName}/perm/users
     *
     * <p>Adds {@link Permission}s of the specific users to the specified {@code repoName} in the
     * specified {@code projectName}.
     */
    @Post("/metadata/{projectName}/repos/{repoName}/perm/users")
    @Decorator(ProjectOwnersOnly.class)
    public CompletableFuture<Revision> addSpecificUserPermission(@Param("projectName") String projectName,
                                                                 @Param("repoName") String repoName,
                                                                 @RequestObject IdentifierWithPermissions
                                                                         memberWithPermissions,
                                                                 @RequestObject Author author) {
        final User member = new User(urlDecode(memberWithPermissions.id()));
        return mds.addPerUserPermission(author, projectName, repoName,
                                        member, memberWithPermissions.permissions());
    }

    /**
     * PATCH /metadata/{projectName}/repos/{repoName}/perm/users/{memberId}
     *
     * <p>Updates {@link Permission}s for the specified {@code memberId} of the specified {@code repoName}
     * in the specified {@code projectName}.
     */
    @Patch("/metadata/{projectName}/repos/{repoName}/perm/users/{memberId}")
    @ConsumeType("application/json-patch+json")
    @Decorator(ProjectOwnersOnly.class)
    public CompletableFuture<Revision> updateSpecificUserPermission(@Param("projectName") String projectName,
                                                                    @Param("repoName") String repoName,
                                                                    @Param("memberId") String memberId,
                                                                    @RequestObject JsonPatch jsonPatch,
                                                                    @RequestObject Author author) {
        final ReplaceOperation operation = ensureSingleReplaceOperation(jsonPatch, "/permissions");
        final Collection<Permission> permissions = Jackson.convertValue(operation.value(), permissionsTypeRef);
        final User user = new User(urlDecode(memberId));
        return mds.findPermissions(projectName, repoName, user)
                  .thenCompose(unused -> mds.updatePerUserPermission(author,
                                                                     projectName, repoName, user,
                                                                     permissions));
    }

    /**
     * DELETE /metadata/{projectName}/repos/{repoName}/perm/users/{memberId}
     *
     * <p>Removes {@link Permission}s for the specified {@code memberId} from the specified {@code repoName}
     * in the specified {@code projectName}.
     */
    @Delete("/metadata/{projectName}/repos/{repoName}/perm/users/{memberId}")
    @Decorator(ProjectOwnersOnly.class)
    public CompletableFuture<Revision> removeSpecificUserPermission(@Param("projectName") String projectName,
                                                                    @Param("repoName") String repoName,
                                                                    @Param("memberId") String memberId,
                                                                    @RequestObject Author author) {
        final User user = new User(urlDecode(memberId));
        return mds.findPermissions(projectName, repoName, user)
                  .thenCompose(unused -> mds.removePerUserPermission(author, projectName,
                                                                     repoName, user));
    }

    /**
     * POST /metadata/{projectName}/repos/{repoName}/perm/tokens
     *
     * <p>Adds {@link Permission}s for a token to the specified {@code repoName} in the specified
     * {@code projectName}.
     */
    @Post("/metadata/{projectName}/repos/{repoName}/perm/tokens")
    @Decorator(ProjectOwnersOnly.class)
    public CompletableFuture<Revision> addSpecificTokenPermission(@Param("projectName") String projectName,
                                                                  @Param("repoName") String repoName,
                                                                  @RequestObject IdentifierWithPermissions
                                                                          tokenWithPermissions,
                                                                  @RequestObject Author author) {
        return mds.addPerTokenPermission(author, projectName, repoName,
                                         tokenWithPermissions.id(), tokenWithPermissions.permissions());
    }

    /**
     * PATCH /metadata/{projectName}/repos/{repoName}/perm/tokens/{appId}
     *
     * <p>Updates {@link Permission}s for the specified {@code appId} of the specified {@code repoName}
     * in the specified {@code projectName}.
     */
    @Patch("/metadata/{projectName}/repos/{repoName}/perm/tokens/{appId}")
    @ConsumeType("application/json-patch+json")
    @Decorator(ProjectOwnersOnly.class)
    public CompletableFuture<Revision> updateSpecificTokenPermission(@Param("projectName") String projectName,
                                                                     @Param("repoName") String repoName,
                                                                     @Param("appId") String appId,
                                                                     @RequestObject JsonPatch jsonPatch,
                                                                     @RequestObject Author author) {
        final ReplaceOperation operation = ensureSingleReplaceOperation(jsonPatch, "/permissions");
        final Collection<Permission> permissions = Jackson.convertValue(operation.value(), permissionsTypeRef);
        return mds.findTokenByAppId(appId)
                  .thenCompose(token -> mds.updatePerTokenPermission(
                          author, projectName, repoName, appId, permissions));
    }

    /**
     * DELETE /metadata/{projectName}/repos/{repoName}/perm/tokens/{appId}
     *
     * <p>Removes {@link Permission}s of the specified {@code appId} from the specified {@code repoName}
     * in the specified {@code projectName}.
     */
    @Delete("/metadata/{projectName}/repos/{repoName}/perm/tokens/{appId}")
    @Decorator(ProjectOwnersOnly.class)
    public CompletableFuture<Revision> removeSpecificTokenPermission(@Param("projectName") String projectName,
                                                                     @Param("repoName") String repoName,
                                                                     @Param("appId") String appId,
                                                                     @RequestObject Author author) {
        return mds.findTokenByAppId(appId)
                  .thenCompose(token -> mds.removePerTokenPermission(author,
                                                                     projectName, repoName, appId));
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

    private static String urlDecode(String input) {
        try {
            // TODO(hyangtack) Remove this after https://github.com/line/armeria/issues/756 is resolved.
            return URLDecoder.decode(input, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    private static ProjectRole toProjectRole(String roleStr) {
        final ProjectRole role = ProjectRole.valueOf(requireNonNull(roleStr, "roleStr"));
        checkArgument(role == ProjectRole.OWNER || role == ProjectRole.MEMBER,
                      "Invalid role: " + role +
                      " (expected: '" + ProjectRole.OWNER + "' or '" + ProjectRole.MEMBER + "')");
        return role;
    }

    // TODO(hyangtack) Move these classes to the common module later when our java client accesses to
    //                 the metadata.
    static final class IdentifierWithRole {

        private final String id;
        private final String role;

        @JsonCreator
        IdentifierWithRole(@JsonProperty("id") String id,
                           @JsonProperty("role") String role) {
            this.id = requireNonNull(id, "id");
            this.role = requireNonNull(role, "role");
        }

        @JsonProperty
        public String id() {
            return id;
        }

        @JsonProperty
        public String role() {
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

    static final class IdentifierWithPermissions {

        private final String id;
        private final Collection<Permission> permissions;

        @JsonCreator
        IdentifierWithPermissions(@JsonProperty("id") String id,
                                  @JsonProperty("permissions") Collection<Permission> permissions) {
            this.id = requireNonNull(id, "id");
            this.permissions = requireNonNull(permissions, "permissions");
        }

        @JsonProperty
        public String id() {
            return id;
        }

        @JsonProperty
        public Collection<Permission> permissions() {
            return permissions;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("id", id())
                              .add("permissions", permissions())
                              .toString();
        }
    }
}
