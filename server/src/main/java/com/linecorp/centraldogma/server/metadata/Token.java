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
public final class Token implements Identifiable {

    /**
     * An application identifier.
     */
    private final String appId;

    /**
     * A secret which is used to access an HTTP API.
     */
    @Nullable
    private final String secret;

    /**
     * Specifies whether this token is for system administrators.
     */
    private final boolean isSystemAdmin;

    /**
     * Specifies when this token is created by whom.
     */
    private final UserAndTimestamp creation;

    /**
     * Specifies when this repository is removed by whom.
     */
    @Nullable
    private final UserAndTimestamp deactivation;

    @Nullable
    private final UserAndTimestamp deletion;

    Token(String appId, String secret, boolean isSystemAdmin, UserAndTimestamp creation) {
        this(appId, secret, null, isSystemAdmin, creation, null, null);
    }

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public Token(@JsonProperty("appId") String appId,
                 @JsonProperty("secret") String secret,
                 // TODO(minwoox): Remove admin field after all tokens are migrated.
                 @JsonProperty("admin") @Nullable Boolean isAdmin,
                 @JsonProperty("systemAdmin") @Nullable Boolean isSystemAdmin,
                 @JsonProperty("creation") UserAndTimestamp creation,
                 @JsonProperty("deactivation") @Nullable UserAndTimestamp deactivation,
                 @JsonProperty("deletion") @Nullable UserAndTimestamp deletion) {
        assert isAdmin != null || isSystemAdmin != null;
        this.appId = Util.validateFileName(appId, "appId");
        this.secret = Util.validateFileName(secret, "secret");
        this.isSystemAdmin = isSystemAdmin != null ? isSystemAdmin : isAdmin;
        this.creation = requireNonNull(creation, "creation");
        this.deactivation = deactivation;
        this.deletion = deletion;
    }

    private Token(String appId, boolean isSystemAdmin, UserAndTimestamp creation,
                  @Nullable UserAndTimestamp deactivation, @Nullable UserAndTimestamp deletion) {
        this.appId = Util.validateFileName(appId, "appId");
        this.isSystemAdmin = isSystemAdmin;
        this.creation = requireNonNull(creation, "creation");
        this.deactivation = deactivation;
        this.deletion = deletion;
        secret = null;
    }

    @Override
    public String id() {
        return appId;
    }

    /**
     * Returns the ID of the application.
     */
    @JsonProperty
    public String appId() {
        return appId;
    }

    /**
     * Returns the secret.
     */
    @Nullable
    @JsonProperty
    public String secret() {
        return secret;
    }

    /**
     * Returns whether this token has system administrative privileges.
     */
    @JsonProperty
    public boolean isSystemAdmin() {
        return isSystemAdmin;
    }

    /**
     * Returns who created this token when.
     */
    @JsonProperty
    public UserAndTimestamp creation() {
        return creation;
    }

    /**
     * Returns who deactivated this token when.
     */
    @Nullable
    @JsonProperty
    public UserAndTimestamp deactivation() {
        return deactivation;
    }

    /**
     * Returns who deleted this token when.
     */
    @Nullable
    @JsonProperty
    public UserAndTimestamp deletion() {
        return deletion;
    }

    /**
     * Returns whether this token is activated.
     */
    @JsonIgnore
    public boolean isActive() {
        return deactivation == null && deletion == null;
    }

    /**
     * Returns whether this token is deleted.
     */
    @JsonIgnore
    public boolean isDeleted() {
        return deletion != null;
    }

    @Override
    public String toString() {
        // Do not add "secret" to prevent it from logging.
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("appId", appId())
                          .add("isSystemAdmin", isSystemAdmin())
                          .add("creation", creation())
                          .add("deactivation", deactivation())
                          .add("deletion", deletion())
                          .toString();
    }

    /**
     * Returns a new {@link Token} instance without its secret.
     */
    public Token withoutSecret() {
        return new Token(appId(), isSystemAdmin(), creation(), deactivation(), deletion());
    }

    /**
     * Returns a new {@link Token} instance without its secret.
     * This method must be called by the token whose secret is not null.
     */
    public Token withSystemAdmin(boolean isSystemAdmin) {
        if (isSystemAdmin == isSystemAdmin()) {
            return this;
        }
        final String secret = secret();
        assert secret != null;
        return new Token(appId(), secret, null, isSystemAdmin, creation(), deactivation(), deletion());
    }
}
