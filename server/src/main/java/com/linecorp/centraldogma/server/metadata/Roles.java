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
import java.util.Map.Entry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.server.storage.repository.HasWeight;

/**
 * Role metadata for a repository.
 */
public final class Roles implements HasWeight {

    private final ProjectRoles projectRoles;

    private final Map<String, RepositoryRole> users;

    private final Map<String, RepositoryRole> tokens;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public Roles(@JsonProperty("projects") ProjectRoles projectRoles,
                 @JsonProperty("users") Map<String, RepositoryRole> users,
                 @JsonProperty("tokens") Map<String, RepositoryRole> tokens) {
        this.projectRoles = requireNonNull(projectRoles, "projectRoles");
        this.users = requireNonNull(users, "users");
        this.tokens = requireNonNull(tokens, "tokens");
    }

    /**
     * Returns the {@link ProjectRoles} of the repository.
     */
    @JsonProperty("projects")
    public ProjectRoles projectRoles() {
        return projectRoles;
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
    public int weight() {
        int weight = 0;
        final RepositoryRole member = projectRoles.member();
        if (member != null) {
            weight += member.name().length();
        }
        for (Entry<String, RepositoryRole> entry : users.entrySet()) {
            weight += entry.getKey().length();
            weight += entry.getValue().name().length();
        }
        for (Entry<String, RepositoryRole> entry : tokens.entrySet()) {
            weight += entry.getKey().length();
            weight += entry.getValue().name().length();
        }
        return weight;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Roles)) {
            return false;
        }
        final Roles other = (Roles) o;
        return projectRoles.equals(other.projectRoles) &&
               users.equals(other.users) &&
               tokens.equals(other.tokens);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(projectRoles, users, tokens);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("projectRoles", projectRoles)
                          .add("users", users)
                          .add("tokens", tokens)
                          .toString();
    }
}
