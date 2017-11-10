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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * Specifies details of an application token.
 */
@JsonInclude(Include.NON_NULL)
public class TokenInfo {

    private final String appId;
    private final String secret;
    private final ProjectRole role;
    private final UserAndTimestamp creation;

    @JsonCreator
    public TokenInfo(@JsonProperty("appId") String appId,
                     @JsonProperty("secret") String secret,
                     @JsonProperty("role") ProjectRole role,
                     @JsonProperty("creation") UserAndTimestamp creation) {
        this.appId = requireNonNull(appId, "appId");
        this.secret = requireNonNull(secret, "secret");
        this.role = requireNonNull(role, "role");
        this.creation = requireNonNull(creation, "creation");
    }

    private TokenInfo(String appId, ProjectRole role, UserAndTimestamp creation) {
        this.appId = requireNonNull(appId, "appId");
        this.role = requireNonNull(role, "role");
        this.creation = requireNonNull(creation, "creation");
        secret = null;
    }

    @JsonProperty
    public String appId() {
        return appId;
    }

    @JsonProperty
    public String secret() {
        return secret;
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
        // Do not add "secret" to prevent it from logging.
        return MoreObjects.toStringHelper(this)
                          .add("appId", appId())
                          .add("role", role())
                          .add("creation", creation())
                          .toString();
    }

    /**
     * Returns a new {@link TokenInfo} instance without its secret.
     */
    public TokenInfo withoutSecret() {
        return new TokenInfo(appId(), role(), creation());
    }
}
