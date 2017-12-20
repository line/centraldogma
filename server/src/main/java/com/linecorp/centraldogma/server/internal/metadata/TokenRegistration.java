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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * Specifies a registration of a {@link Token}.
 */
@JsonInclude(Include.NON_NULL)
public class TokenRegistration implements Identifiable {

    /**
     * An application identifier which belongs to a {@link Token}.
     */
    private final String appId;

    /**
     * A role of the {@link Token} in a project.
     */
    private final ProjectRole role;

    /**
     * Specifies when the token is registered by whom.
     */
    private final UserAndTimestamp creation;

    @JsonCreator
    public TokenRegistration(@JsonProperty("appId") String appId,
                             @JsonProperty("role") ProjectRole role,
                             @JsonProperty("creation") UserAndTimestamp creation) {
        this.appId = requireNonNull(appId, "appId");
        this.role = requireNonNull(role, "role");
        this.creation = requireNonNull(creation, "creation");
    }

    @Override
    public String id() {
        return appId;
    }

    @JsonProperty
    public String appId() {
        return appId;
    }

    @JsonProperty
    public ProjectRole role() {
        return role;
    }

    @JsonProperty
    public UserAndTimestamp creation() {
        return creation;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("appId", appId())
                          .add("role", role())
                          .add("creation", creation())
                          .toString();
    }
}
