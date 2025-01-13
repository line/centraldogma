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

package com.linecorp.centraldogma.server.internal.mirror;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.internal.api.sysadmin.MirrorAccessControlRequest;
import com.linecorp.centraldogma.server.internal.storage.repository.HasId;
import com.linecorp.centraldogma.server.metadata.UserAndTimestamp;

public final class MirrorAccessControl implements HasId<MirrorAccessControl> {

    static MirrorAccessControl from(MirrorAccessControlRequest request, Author author) {
        return new MirrorAccessControl(request.id(), request.targetPattern(), request.allow(),
                                       request.description(), request.order(), UserAndTimestamp.of(author));
    }

    private final String id;
    private final String targetPattern;
    private final boolean allow;
    private final String description;
    private final int order;
    private final UserAndTimestamp creation;

    @JsonCreator
    MirrorAccessControl(@JsonProperty("id") String id,
                        @JsonProperty("targetPattern") String targetPattern,
                        @JsonProperty("allow") boolean allow,
                        @JsonProperty("description") String description,
                        @JsonProperty("order") int order,
                        @JsonProperty("creation") UserAndTimestamp creation) {
        this.id = requireNonNull(id, "id");
        this.targetPattern = requireNonNull(targetPattern, "targetPattern");
        this.allow = allow;
        this.description = requireNonNull(description, "description");
        this.creation = requireNonNull(creation, "creation");
        this.order = order;
    }

    /**
     * Returns the ID of the mirror access control.
     */
    @Override
    @JsonProperty
    public String id() {
        return id;
    }

    /**
     * Returns the target pattern of the mirror.
     */
    @JsonProperty
    public String targetPattern() {
        return targetPattern;
    }

    /**
     * Returns whether the mirror ACL allows or denies the target pattern.
     */
    @JsonProperty
    public boolean allow() {
        return allow;
    }

    /**
     * Returns the description of the mirror access control.
     */
    @JsonProperty
    public String description() {
        return description;
    }

    /**
     * Returns the order of the mirror access control.
     */
    @JsonProperty
    public int order() {
        return order;
    }

    /**
     * Returns who creates the mirror ACL when.
     */
    @JsonProperty
    public UserAndTimestamp creation() {
        return creation;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MirrorAccessControl)) {
            return false;
        }
        final MirrorAccessControl that = (MirrorAccessControl) o;
        return allow == that.allow &&
               order == that.order &&
               id.equals(that.id) &&
               targetPattern.equals(that.targetPattern) &&
               description.equals(that.description) &&
               creation.equals(that.creation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, order, targetPattern, allow, description, creation);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("id", id)
                          .add("targetPattern", targetPattern)
                          .add("allow", allow)
                          .add("description", description)
                          .add("order", order)
                          .add("creation", creation)
                          .toString();
    }
}
