/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.admin.model;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * A default permission for a {@link Repository}.
 */
public class DefaultPermission {

    /**
     * A permission for a project owner.
     */
    private final Permission owner;

    /**
     * A permission for a project member.
     */
    private final Permission member;

    /**
     * A permission for a user who is neither a project owner nor a member.
     */
    private final Permission guest;

    /**
     * Creates an instance with default permission.
     */
    public DefaultPermission() {
        this(Permission.READ_WRITE, Permission.READ_WRITE, Permission.READ_ONLY);
    }

    @JsonCreator
    public DefaultPermission(@JsonProperty("owner") Permission owner,
                             @JsonProperty("member") Permission member,
                             @JsonProperty("guest") Permission guest) {
        this.owner = requireNonNull(owner, "owner");
        this.member = requireNonNull(member, "member");
        this.guest = requireNonNull(guest, "guest");
    }

    @JsonProperty
    public Permission owner() {
        return owner;
    }

    @JsonProperty
    public Permission member() {
        return member;
    }

    @JsonProperty
    public Permission guest() {
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
