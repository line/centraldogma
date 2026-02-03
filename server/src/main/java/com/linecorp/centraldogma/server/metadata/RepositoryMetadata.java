/*
 * Copyright 2019 LINE Corporation
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

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.RepositoryStatus;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.HasWeight;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * Specifies details of a {@link Repository}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL) // These are used when serializing.
public final class RepositoryMetadata implements Identifiable, HasWeight {

    public static final ProjectRoles DEFAULT_PROJECT_ROLES = ProjectRoles.of(RepositoryRole.WRITE, null);

    /**
     * Creates a new instance with default properties.
     */
    public static RepositoryMetadata of(String name, UserAndTimestamp creation) {
        return new RepositoryMetadata(name, creation, DEFAULT_PROJECT_ROLES);
    }

    /**
     * Creates a new instance with default properties.
     */
    public static RepositoryMetadata of(String name, Roles roles, UserAndTimestamp creation) {
        requireNonNull(name, "name");
        requireNonNull(roles, "roles");
        requireNonNull(creation, "creation");
        return new RepositoryMetadata(name, roles, creation, null, RepositoryStatus.ACTIVE);
    }

    /**
     * Creates a new instance.
     */
    public static RepositoryMetadata of(String name, UserAndTimestamp creation, ProjectRoles projectRoles) {
        return new RepositoryMetadata(name, creation, projectRoles);
    }

    /**
     * Creates a new instance for dogma repository.
     */
    public static RepositoryMetadata ofDogma(RepositoryStatus repositoryStatus) {
        return new RepositoryMetadata(Project.REPO_DOGMA, Roles.EMPTY, null, null, repositoryStatus);
    }

    /**
     * A name of this repository.
     */
    private final String name;

    private final Roles roles;

    /**
     * Specifies when this repository is created by whom.
     */
    @Nullable
    private final UserAndTimestamp creation;

    /**
     * Specifies when this repository is removed by whom.
     */
    @Nullable
    private final UserAndTimestamp removal;

    private final RepositoryStatus repositoryStatus;

    /**
     * Creates a new instance.
     */
    private RepositoryMetadata(String name, UserAndTimestamp creation, ProjectRoles projectRoles) {
        this(name, new Roles(requireNonNull(projectRoles, "projectRoles"),
                             ImmutableMap.of(), null, ImmutableMap.of()),
             creation, /* removal */ null, RepositoryStatus.ACTIVE);
    }

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public RepositoryMetadata(@JsonProperty("name") String name,
                              @JsonProperty("roles") Roles roles,
                              @JsonProperty("creation") @Nullable UserAndTimestamp creation,
                              @JsonProperty("removal") @Nullable UserAndTimestamp removal,
                              @JsonProperty("status") @Nullable RepositoryStatus repositoryStatus) {
        this.name = requireNonNull(name, "name");
        this.roles = requireNonNull(roles, "roles");
        if (!Project.REPO_DOGMA.equals(name)) {
            requireNonNull(creation, "creation");
        }
        this.creation = creation;
        this.removal = removal;
        this.repositoryStatus = firstNonNull(repositoryStatus, RepositoryStatus.ACTIVE);
    }

    @Override
    public String id() {
        return name;
    }

    /**
     * Returns the repository name.
     */
    @JsonProperty
    public String name() {
        return name;
    }

    /**
     * Returns the {@link Roles} of this repository.
     */
    @JsonProperty
    public Roles roles() {
        return roles;
    }

    /**
     * Returns who created this repository when.
     * This returns {@code null} if this repository is {@link Project#REPO_DOGMA}.
     */
    @Nullable
    @JsonProperty
    public UserAndTimestamp creation() {
        return creation;
    }

    /**
     * Returns who removed this repository when.
     */
    @Nullable
    @JsonProperty
    public UserAndTimestamp removal() {
        return removal;
    }

    /**
     * Returns the {@link RepositoryStatus}.
     */
    @JsonProperty
    public RepositoryStatus status() {
        return repositoryStatus;
    }

    @Override
    public int weight() {
        int weight = 0;
        weight += name.length();
        weight += roles.weight();
        return weight;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final RepositoryMetadata that = (RepositoryMetadata) o;
        return name.equals(that.name) &&
               roles.equals(that.roles) &&
               Objects.equals(creation, that.creation) &&
               Objects.equals(removal, that.removal) &&
               repositoryStatus == that.repositoryStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, roles, creation, removal, repositoryStatus);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("name", name)
                          .add("roles", roles)
                          .add("creation", creation)
                          .add("removal", removal)
                          .add("repositoryStatus", repositoryStatus)
                          .toString();
    }
}
