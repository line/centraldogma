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
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Sets;

import com.linecorp.centraldogma.server.storage.repository.Repository;

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

    /**
     * The default permission.
     *
     * @deprecated Use {@link #ofDefault()}.
     */
    @Deprecated
    public static final PerRolePermissions DEFAULT =
            new PerRolePermissions(READ_WRITE, READ_WRITE, NO_PERMISSION, NO_PERMISSION);
    private static final PerRolePermissions internalPermissions =
            new PerRolePermissions(READ_WRITE, NO_PERMISSION, NO_PERMISSION, NO_PERMISSION);

    /**
     * Creates a {@link PerRolePermissions} which allows read/write a repository from a owner.
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
        return new PerRolePermissions(READ_WRITE, READ_WRITE, READ_WRITE, NO_PERMISSION);
    }

    /**
     * Creates a {@link PerRolePermissions} which allows accessing a repository from a project member.
     */
    public static PerRolePermissions ofPrivate() {
        return new PerRolePermissions(READ_WRITE, READ_WRITE, NO_PERMISSION, NO_PERMISSION);
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
     * {@link Permission}s for a {@link ProjectRole#ANONYMOUS}.
     */
    private final Set<Permission> anonymous;

    /**
     * Creates an instance.
     */
    @JsonCreator
    public PerRolePermissions(@JsonProperty("owner") Iterable<Permission> owner,
                              @JsonProperty("member") Iterable<Permission> member,
                              @JsonProperty("guest") Iterable<Permission> guest,
                              @JsonProperty("anonymous") Iterable<Permission> anonymous) {
        this.owner = Sets.immutableEnumSet(requireNonNull(owner, "owner"));
        this.member = Sets.immutableEnumSet(requireNonNull(member, "member"));
        this.guest = Sets.immutableEnumSet(requireNonNull(guest, "guest"));
        this.anonymous = Sets.immutableEnumSet(requireNonNull(anonymous, "anonymous"));
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

    /**
     * Returns the permissions granted to anonymous users.
     */
    @JsonProperty
    public Set<Permission> anonymous() {
        return anonymous;
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, member, guest, anonymous);
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
               guest.equals(that.guest) &&
               anonymous.equals(that.anonymous);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("owner", owner())
                          .add("member", member())
                          .add("guest", guest())
                          .add("anonymous", anonymous())
                          .toString();
    }
}
