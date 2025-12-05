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

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.centraldogma.internal.Util;

/**
 * Specifies details of an application token.
 */
public final class Token extends AbstractApplication {

    /**
     * A secret which is used to access an HTTP API.
     */
    @Nullable
    private final String secret;

    Token(String appId, String secret, boolean isSystemAdmin, boolean allowGuestAccess,
          UserAndTimestamp creation) {
        super(appId, ApplicationType.TOKEN, isSystemAdmin, allowGuestAccess, creation, null, null);
        this.secret = Util.validateFileName(secret, "secret");
    }

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public Token(@JsonProperty("appId") String appId,
                 @JsonProperty("secret") String secret,
                 @JsonProperty("systemAdmin") boolean isSystemAdmin,
                 @JsonProperty("allowGuestAccess") @Nullable Boolean allowGuestAccess,
                 @JsonProperty("creation") UserAndTimestamp creation,
                 @JsonProperty("deactivation") @Nullable UserAndTimestamp deactivation,
                 @JsonProperty("deletion") @Nullable UserAndTimestamp deletion) {
        super(appId, ApplicationType.TOKEN, isSystemAdmin,
              // Allow guest access by default for backward compatibility.
              firstNonNull(allowGuestAccess, true),
              requireNonNull(creation, "creation"),
              deactivation,
              deletion);
        this.secret = Util.validateFileName(secret, "secret");
    }

    private Token(String appId, boolean isSystemAdmin, boolean allowGuestAccess, UserAndTimestamp creation,
                  @Nullable UserAndTimestamp deactivation, @Nullable UserAndTimestamp deletion) {
        super(appId, ApplicationType.TOKEN, isSystemAdmin, allowGuestAccess,
              requireNonNull(creation, "creation"), deactivation, deletion);
        secret = null;
    }

    /**
     * Returns the ID of the application.
     */
    @Override
    @JsonProperty("appId")
    public String appId() {
        return id();
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
     * Returns a new {@link Token} instance without its secret.
     */
    public Token withoutSecret() {
        return new Token(id(), isSystemAdmin(), allowGuestAccess(), creation(), deactivation(), deletion());
    }

    /**
     * Returns a new {@link Token} instance with the specified system admin flag.
     * This method must be called by the token whose secret is not null.
     */
    @Override
    public Token withSystemAdmin(boolean isSystemAdmin) {
        if (isSystemAdmin == isSystemAdmin()) {
            return this;
        }
        final String secret = secret();
        assert secret != null;
        return new Token(id(), secret, isSystemAdmin, allowGuestAccess(), creation(),
                         deactivation(), deletion());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), secret);
    }

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }

        final Token that = (Token) o;
        return Objects.equal(secret, that.secret);
    }

    @Override
    void addProperties(MoreObjects.ToStringHelper helper) {
        // Do not add "secret" to prevent it from logging.
    }
}
