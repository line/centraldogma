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

package com.linecorp.centraldogma.internal.api.v1;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Ascii;
import com.google.common.base.MoreObjects;

public class AccessToken {

    private static final String BEARER = "Bearer";

    private final String accessToken;

    private final long expiresIn;

    private final String refreshToken;

    private final long deadline;

    //TODO(minwoox) Add scope if needed.

    public AccessToken(String accessToken, long expiresIn) {
        this(BEARER, accessToken, expiresIn, "");
    }

    @JsonCreator
    public AccessToken(@JsonProperty("token_type") String tokenType,
                       @JsonProperty("access_token") String accessToken,
                       @JsonProperty("expires_in") long expiresIn,
                       @JsonProperty("refresh_token") String refreshToken) {
        requireNonNull(tokenType, "tokenType");
        checkArgument(Ascii.equalsIgnoreCase(tokenType, BEARER),
                      "tokenType: %s (expected: %s)", tokenType, BEARER);
        this.accessToken = requireNonNull(accessToken, "accessToken");
        this.expiresIn = expiresIn;
        this.refreshToken = requireNonNull(refreshToken, "refreshToken");
        deadline = System.currentTimeMillis() + expiresIn;
    }

    @JsonProperty("access_token")
    public String accessToken() {
        return accessToken;
    }

    @JsonProperty("expires_in")
    public long expiresIn() {
        return expiresIn;
    }

    @JsonProperty("token_type")
    public String tokenType() {
        return BEARER;
    }

    @JsonProperty("refresh_token")
    public String refreshToken() {
        return refreshToken;
    }

    public long deadline() {
        return deadline;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("accessToken", accessToken)
                          .add("expiresIn", expiresIn)
                          .add("tokenType", BEARER)
                          .add("deadline", deadline)
                          .toString();
    }
}
