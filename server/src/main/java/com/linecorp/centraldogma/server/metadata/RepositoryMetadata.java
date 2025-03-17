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

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.server.QuotaConfig;
import com.linecorp.centraldogma.server.storage.repository.HasWeight;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * Specifies details of a {@link Repository}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL) // These are used when serializing.
@JsonDeserialize(using = RepositoryMetadataDeserializer.class)
public final class RepositoryMetadata implements Identifiable, HasWeight {

    public static final ProjectRoles DEFAULT_PROJECT_ROLES = ProjectRoles.of(RepositoryRole.WRITE, null);

    private static final ProjectRoles INTERNAL_PROJECT_ROLES = ProjectRoles.of(null, null);

    /**
     * Creates a new instance with default properties.
     */
    public static RepositoryMetadata of(String name, UserAndTimestamp creation) {
        return new RepositoryMetadata(name, creation, DEFAULT_PROJECT_ROLES);
    }

    /**
     * Creates a new instance.
     */
    public static RepositoryMetadata of(String name, UserAndTimestamp creation, ProjectRoles projectRoles) {
        return new RepositoryMetadata(name, creation, projectRoles);
    }

    /**
     * A name of this repository.
     */
    private final String name;

    private final Roles roles;

    /**
     * Specifies when this repository is created by whom.
     */
    private final UserAndTimestamp creation;

    /**
     * Specifies when this repository is removed by whom.
     */
    @Nullable
    private final UserAndTimestamp removal;

    /**
     * A write quota of this repository.
     */
    @Nullable
    private final QuotaConfig writeQuota;

    /**
     * Creates a new instance.
     */
    private RepositoryMetadata(String name, UserAndTimestamp creation, ProjectRoles projectRoles) {
        this(name, new Roles(requireNonNull(projectRoles, "projectRoles"),
                             ImmutableMap.of(), ImmutableMap.of()),
             creation, /* removal */ null, /* writeQuota */ null);
    }

    /**
     * Creates a new instance.
     */
    public RepositoryMetadata(String name, Roles roles, UserAndTimestamp creation,
                              @Nullable UserAndTimestamp removal, @Nullable QuotaConfig writeQuota) {
        this.name = requireNonNull(name, "name");
        this.roles = requireNonNull(roles, "roles");
        this.creation = requireNonNull(creation, "creation");
        this.removal = removal;
        this.writeQuota = writeQuota;
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
     */
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
     * Returns the maximum allowed write quota.
     */
    @Nullable
    @JsonProperty("writeQuota")
    public QuotaConfig writeQuota() {
        return writeQuota;
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
               creation.equals(that.creation) && Objects.equals(removal, that.removal) &&
               Objects.equals(writeQuota, that.writeQuota);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, roles, creation, removal, writeQuota);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("name", name)
                          .add("roles", roles)
                          .add("creation", creation)
                          .add("removal", removal)
                          .add("writeQuota", writeQuota)
                          .toString();
    }
}
