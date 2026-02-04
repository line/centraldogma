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

package com.linecorp.centraldogma.server.internal.api.sysadmin;

import static com.linecorp.centraldogma.server.internal.storage.repository.git.DefaultCrudOperation.validateId;
import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

public final class MirrorAccessControlRequest {
    private final String id;
    private final String targetPattern;
    private final boolean allow;
    private final String description;
    private final int order;

    @JsonCreator
    public MirrorAccessControlRequest(@JsonProperty("id") String id,
                                      @JsonProperty("targetPattern") String targetPattern,
                                      @JsonProperty("allow") boolean allow,
                                      @JsonProperty("description") String description,
                                      @JsonProperty("order") int order) {
        this.id = validateId(id);
        // Validate the target pattern.
        try {
            Pattern.compile(targetPattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid targetPattern: " + targetPattern, e);
        }
        this.targetPattern = requireNonNull(targetPattern, "targetPattern");
        this.allow = allow;
        this.description = requireNonNull(description, "description");
        this.order = order;
    }

    /**
     * Returns the ID of the mirror access control.
     */
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

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MirrorAccessControlRequest)) {
            return false;
        }
        final MirrorAccessControlRequest that = (MirrorAccessControlRequest) o;
        return allow == that.allow &&
               order == that.order &&
               id.equals(that.id) &&
               targetPattern.equals(that.targetPattern) &&
               description.equals(that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, targetPattern, allow, description, order);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("id", id)
                          .add("targetPattern", targetPattern)
                          .add("allow", allow)
                          .add("description", description)
                          .add("order", order)
                          .toString();
    }
}
