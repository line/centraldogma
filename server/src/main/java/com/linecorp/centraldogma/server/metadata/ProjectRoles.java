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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.centraldogma.common.RepositoryRole;

/**
 * Represents the roles assigned to project members and guests for a specific repository.
 */
public final class ProjectRoles {

    private static final ProjectRoles EMPTY = new ProjectRoles(null, null);

    /**
     * Returns a new {@link ProjectRoles} with the specified {@link RepositoryRole}s.
     */
    public static ProjectRoles of(@Nullable RepositoryRole member, @Nullable RepositoryRole guest) {
        if (member == null && guest == null) {
            return EMPTY;
        }
        return new ProjectRoles(member, guest);
    }

    @Nullable
    private final RepositoryRole member;

    @Nullable
    private final RepositoryRole guest;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public ProjectRoles(@JsonProperty("member") @Nullable RepositoryRole member,
                        @JsonProperty("guest") @Nullable RepositoryRole guest) {
        this.member = member;
        this.guest = guest;
    }

    /**
     * Returns the role assigned to project members for this repository.
     */
    @Nullable
    @JsonProperty("member")
    public RepositoryRole member() {
        return member;
    }

    /**
     * Returns the role assigned to project guests for this repository.
     */
    @Nullable
    @JsonProperty("guest")
    public RepositoryRole guest() {
        return guest;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("member", member)
                          .add("guest", guest)
                          .toString();
    }
}
