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

package com.linecorp.centraldogma.server.internal.metadata;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * Specifies details of a {@link Repository}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public class RepositoryMetadata implements Identifiable {

    /**
     * A name of this repository.
     */
    private final String name;

    /**
     * A default permission of this repository which is based on a {@link ProjectRole} of a user.
     */
    private final PerRolePermissions perRolePermissions;

    /**
     * A map of username and {@link Permission}s who has permission specified by a owner.
     */
    private final Map<String, Collection<Permission>> perUserPermissions;

    /**
     * A map of token ID and {@link Permission}s who has permission specified by a owner.
     */
    private final Map<String, Collection<Permission>> perTokenPermissions;

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
     * Creates a new repository with default properties.
     */
    public RepositoryMetadata(String name, UserAndTimestamp creation, PerRolePermissions perRolePermissions) {
        this(name, perRolePermissions, ImmutableMap.of(), ImmutableMap.of(), creation, null);
    }

    @JsonCreator
    public RepositoryMetadata(@JsonProperty("name") String name,
                              @JsonProperty("perRolePermissions") PerRolePermissions perRolePermissions,
                              @JsonProperty("perUserPermissions")
                                      Map<String, Collection<Permission>> perUserPermissions,
                              @JsonProperty("perTokenPermissions")
                                      Map<String, Collection<Permission>> perTokenPermissions,
                              @JsonProperty("creation") UserAndTimestamp creation,
                              @JsonProperty("removal") @Nullable UserAndTimestamp removal) {
        this.name = requireNonNull(name, "name");
        this.perRolePermissions = requireNonNull(perRolePermissions, "perRolePermissions");
        this.perUserPermissions = ImmutableMap.copyOf(requireNonNull(perUserPermissions,
                                                                     "perUserPermissions"));
        this.perTokenPermissions = ImmutableMap.copyOf(requireNonNull(perTokenPermissions,
                                                                      "perTokenPermissions"));
        this.creation = requireNonNull(creation, "creation");
        this.removal = removal;
    }

    @Override
    public String id() {
        return name;
    }

    @JsonProperty
    public String name() {
        return name;
    }

    @JsonProperty
    public PerRolePermissions perRolePermissions() {
        return perRolePermissions;
    }

    @JsonProperty
    public Map<String, Collection<Permission>> perUserPermissions() {
        return perUserPermissions;
    }

    @JsonProperty
    public Map<String, Collection<Permission>> perTokenPermissions() {
        return perTokenPermissions;
    }

    @JsonProperty
    public UserAndTimestamp creation() {
        return creation;
    }

    @Nullable
    @JsonProperty
    public UserAndTimestamp removal() {
        return removal;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name())
                          .add("perRolePermissions", perRolePermissions())
                          .add("perUserPermissions", perUserPermissions())
                          .add("perTokenPermissions", perTokenPermissions())
                          .add("creation", creation())
                          .add("removal", removal())
                          .toString();
    }
}
