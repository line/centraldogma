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
import java.util.EnumSet;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * A default permission for a {@link Repository}.
 */
public class PerRolePermissions {

    /**
     * {@link Permission}s for administrators.
     */
    public static final Collection<Permission> ALL_PERMISSION = EnumSet.allOf(Permission.class);

    public static final Collection<Permission> READ_WRITE = EnumSet.of(Permission.READ, Permission.WRITE);
    public static final Collection<Permission> READ_ONLY = EnumSet.of(Permission.READ);
    public static final Collection<Permission> NO_PERMISSION = EnumSet.noneOf(Permission.class);

    public static final PerRolePermissions DEFAULT = new PerRolePermissions(READ_WRITE, READ_WRITE, READ_WRITE);

    /**
     * Creates a {@link PerRolePermissions} which allows accessing a repository from everyone.
     */
    public static PerRolePermissions ofPublic() {
        return new PerRolePermissions(READ_WRITE, READ_WRITE, READ_WRITE);
    }

    /**
     * Creates a {@link PerRolePermissions} which allows accessing a repository from a project member.
     */
    public static PerRolePermissions ofPrivate() {
        return new PerRolePermissions(READ_WRITE, READ_WRITE, NO_PERMISSION);
    }

    /**
     * {@link Permission}s for a {@link ProjectRole#OWNER}.
     */
    private final Collection<Permission> owner;

    /**
     * {@link Permission}s for a {@link ProjectRole#MEMBER}.
     */
    private final Collection<Permission> member;

    /**
     * {@link Permission}s for a {@link ProjectRole#GUEST}.
     */
    private final Collection<Permission> guest;

    /**
     * Creates an instance.
     */
    @JsonCreator
    public PerRolePermissions(@JsonProperty("owner") Collection<Permission> owner,
                              @JsonProperty("member") Collection<Permission> member,
                              @JsonProperty("guest") Collection<Permission> guest) {
        this.owner = copyOf(requireNonNull(owner, "owner"));
        this.member = copyOf(requireNonNull(member, "member"));
        this.guest = copyOf(requireNonNull(guest, "guest"));
    }

    private static EnumSet<Permission> copyOf(Collection<Permission> input) {
        // Avoid IllegalArgumentException raised from EnumSet.copyOf() when the input is empty.
        if (input.isEmpty()) {
            return EnumSet.noneOf(Permission.class);
        }
        return EnumSet.copyOf(input);
    }

    @JsonProperty
    public Collection<Permission> owner() {
        return owner;
    }

    @JsonProperty
    public Collection<Permission> member() {
        return member;
    }

    @JsonProperty
    public Collection<Permission> guest() {
        return guest;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("owner", owner())
                          .add("member", member())
                          .add("guest", guest())
                          .toString();
    }
}
