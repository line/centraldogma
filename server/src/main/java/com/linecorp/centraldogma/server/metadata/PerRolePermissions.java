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

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * A default permission for a {@link Repository}.
 */
public final class PerRolePermissions {

    /**
     * {@link Permission}s for administrators.
     */
    public static final Collection<Permission> READ_WRITE = ImmutableList.of(Permission.READ, Permission.WRITE);
    public static final Collection<Permission> READ_ONLY = ImmutableList.of(Permission.READ);
    public static final Collection<Permission> NO_PERMISSION = ImmutableList.of();

    /**
     * The default permission.
     *
     * @deprecated Use {@link #ofDefault()}.
     */
    @Deprecated
    public static final PerRolePermissions DEFAULT =
            new PerRolePermissions(READ_WRITE, READ_WRITE, NO_PERMISSION, null);
    private static final PerRolePermissions internalPermissions =
            new PerRolePermissions(READ_WRITE, NO_PERMISSION, NO_PERMISSION, null);

    /**
     * Creates a {@link PerRolePermissions} which allows read/write a repository from an owner.
     */
    public static PerRolePermissions ofInternal() {
        return internalPermissions;
    }

    /**
     * Creates a {@link PerRolePermissions} which allows read/write to owners and members.
     */
    public static PerRolePermissions ofDefault() {
        return DEFAULT;
    }

    /**
     * Creates a {@link PerRolePermissions} which allows accessing a repository from everyone.
     */
    public static PerRolePermissions ofPublic() {
        return new PerRolePermissions(READ_WRITE, READ_WRITE, READ_WRITE, null);
    }

    /**
     * Creates a {@link PerRolePermissions} which allows accessing a repository from a project member.
     */
    public static PerRolePermissions ofPrivate() {
        return new PerRolePermissions(READ_WRITE, READ_WRITE, NO_PERMISSION, null);
    }

    /**
     * {@link Permission}s for a {@link ProjectRole#OWNER}.
     */
    private final Set<Permission> owner;

    /**
     * {@link Permission}s for a {@link ProjectRole#MEMBER}.
     */
    private final Set<Permission> member;

    /**
     * {@link Permission}s for a {@link ProjectRole#GUEST}.
     */
    private final Set<Permission> guest;

    /**
     * Creates an instance.
     */
    @JsonCreator
    public PerRolePermissions(@JsonProperty("owner") Iterable<Permission> owner,
                              @JsonProperty("member") Iterable<Permission> member,
                              @JsonProperty("guest") Iterable<Permission> guest,
                              // TODO(minwoox): Remove anonymous field after the migration.
                              @JsonProperty("anonymous") @Nullable Iterable<Permission> unused) {
        this.owner = Sets.immutableEnumSet(requireNonNull(owner, "owner"));
        this.member = Sets.immutableEnumSet(requireNonNull(member, "member"));
        this.guest = Sets.immutableEnumSet(requireNonNull(guest, "guest"));
    }

    /**
     * Returns the permissions granted to owners.
     */
    @JsonProperty
    public Set<Permission> owner() {
        return owner;
    }

    /**
     * Returns the permissions granted to members.
     */
    @JsonProperty
    public Set<Permission> member() {
        return member;
    }

    /**
     * Returns the permissions granted to guests.
     */
    @JsonProperty
    public Set<Permission> guest() {
        return guest;
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, member, guest);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final PerRolePermissions that = (PerRolePermissions) o;
        return owner.equals(that.owner) &&
               member.equals(that.member) &&
               guest.equals(that.guest);
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
