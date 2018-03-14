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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.internal.Util;

/**
 * Specifies details of an application token.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public class Token implements Identifiable {

    /**
     * An application identifier.
     */
    private final String appId;

    /**
     * A secret which is used to access an HTTP API.
     */
    private final String secret;

    /**
     * Specifies whether this token is for administrators.
     */
    private final boolean isAdmin;

    /**
     * Specifies when this token is created by whom.
     */
    private final UserAndTimestamp creation;

    /**
     * Specifies when this repository is removed by whom.
     */
    @Nullable
    private final UserAndTimestamp deactivation;

    Token(String appId, String secret, boolean isAdmin, UserAndTimestamp creation) {
        this(appId, secret, isAdmin, creation, null);
    }

    @JsonCreator
    public Token(@JsonProperty("appId") String appId,
                 @JsonProperty("secret") String secret,
                 @JsonProperty("admin") boolean isAdmin,
                 @JsonProperty("creation") UserAndTimestamp creation,
                 @JsonProperty("deactivation") @Nullable UserAndTimestamp deactivation) {
        this.appId = Util.validateFileName(appId, "appId");
        this.secret = Util.validateFileName(secret, "secret");
        this.isAdmin = isAdmin;
        this.creation = requireNonNull(creation, "creation");
        this.deactivation = deactivation;
    }

    private Token(String appId, boolean isAdmin, UserAndTimestamp creation,
                  @Nullable UserAndTimestamp deactivation) {
        this.appId = Util.validateFileName(appId, "appId");
        this.isAdmin = isAdmin;
        this.creation = requireNonNull(creation, "creation");
        this.deactivation = deactivation;
        secret = null;
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
    public String secret() {
        return secret;
    }

    @JsonProperty
    public boolean isAdmin() {
        return isAdmin;
    }

    @JsonProperty
    public UserAndTimestamp creation() {
        return creation;
    }

    @Nullable
    @JsonProperty
    public UserAndTimestamp deactivation() {
        return deactivation;
    }

    @JsonIgnore
    public boolean isActive() {
        return deactivation == null;
    }

    @Override
    public String toString() {
        // Do not add "secret" to prevent it from logging.
        return MoreObjects.toStringHelper(this)
                          .add("appId", appId())
                          .add("isAdmin", isAdmin())
                          .add("creation", creation())
                          .add("deactivation", deactivation())
                          .toString();
    }

    /**
     * Returns a new {@link Token} instance without its secret.
     */
    public Token withoutSecret() {
        return new Token(appId(), isAdmin(), creation(), deactivation());
    }
}
