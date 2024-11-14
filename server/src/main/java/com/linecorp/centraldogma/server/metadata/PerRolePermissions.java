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

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * A default permission for a {@link Repository}.
 */
@JsonDeserialize(using = PerRolePermissionsDeserializer.class)
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
    public static final PerRolePermissions DEFAULT = new PerRolePermissions(Permission.WRITE, null);

    /**
     * Creates a {@link PerRolePermissions} which allows read/write a repository from an owner.
     */
    public static PerRolePermissions ofInternal() {
        return new PerRolePermissions(null, null);
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
        return new PerRolePermissions(Permission.WRITE, Permission.WRITE);
    }

    /**
     * Creates a {@link PerRolePermissions} which allows accessing a repository from a project member.
     */
    public static PerRolePermissions ofPrivate() {
        return DEFAULT;
    }

    @Nullable
    private final Permission member;

    @Nullable
    private final Permission guest;

    /**
     * Creates an instance.
     */
    public PerRolePermissions(@Nullable Permission member, @Nullable Permission guest) {
        this.member = member;
        this.guest = guest;
    }

    /**
     * Returns the permission granted to members.
     */
    @Nullable
    @JsonProperty
    public Permission member() {
        return member;
    }

    /**
     * Returns the permission granted to guests.
     */
    @Nullable
    @JsonProperty
    public Permission guest() {
        return guest;
    }

    @Override
    public int hashCode() {
        return Objects.hash(member, guest);
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
        return member == that.member && guest == that.guest;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("member", member)
                          .add("guest", guest)
                          .toString();
    }
}
