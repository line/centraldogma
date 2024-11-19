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
package com.linecorp.centraldogma.server.metadata;

import static java.util.Objects.requireNonNull;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Role metadata for a repository.
 */
public final class Roles {

    @Nullable
    private final RepositoryRole projectMember;

    @Nullable
    private final RepositoryRole projectGuest;

    private final Map<String, RepositoryRole> users;

    private final Map<String, RepositoryRole> tokens;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public Roles(@JsonProperty("projectMember") @Nullable RepositoryRole projectMember,
                 // Guest will be removed when we introduce public and private repositories.
                 @JsonProperty("projectGuest") @Nullable RepositoryRole projectGuest,
                 @JsonProperty("users") Map<String, RepositoryRole> users,
                 @JsonProperty("tokens") Map<String, RepositoryRole> tokens) {
        this.projectMember = projectMember;
        this.projectGuest = projectGuest;
        this.users = requireNonNull(users, "users");
        this.tokens = requireNonNull(tokens, "tokens");
    }

    /**
     * Returns the {@link RepositoryRole} of a project member. {@code null} if a member does not have a specific
     * role.
     */
    @Nullable
    @JsonProperty("projectMember")
    public RepositoryRole projectMember() {
        return projectMember;
    }

    /**
     * Returns the {@link RepositoryRole} of a project guest. {@code null} if a guest does not have a specific
     * role.
     */
    @Nullable
    @JsonProperty("projectGuest")
    public RepositoryRole projectGuest() {
        return projectGuest;
    }

    /**
     * Returns the {@link RepositoryRole}s of users.
     */
    @JsonProperty("users")
    public Map<String, RepositoryRole> users() {
        return users;
    }

    /**
     * Returns the {@link RepositoryRole}s of tokens.
     */
    @JsonProperty("tokens")
    public Map<String, RepositoryRole> tokens() {
        return tokens;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("projectMember", projectMember)
                          .add("projectGuest", projectGuest)
                          .add("users", users)
                          .add("tokens", tokens)
                          .toString();
    }
}
