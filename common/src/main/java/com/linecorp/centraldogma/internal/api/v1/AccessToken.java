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

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class AccessToken {

    private static final String BEARER = "Bearer";

    private final String accessToken;

    private final String tokenType = BEARER;

    private final long expiresIn;

    private final String refreshToken;

    //TODO(minwoox) Add scope if needed.

    public AccessToken(String accessToken, long expiresIn) {
        this(accessToken, expiresIn, "");
    }

    @JsonCreator
    public AccessToken(@JsonProperty("access_token") String accessToken,
                       @JsonProperty("expires_in") long expiresIn,
                       @JsonProperty("refresh_token") String refreshToken) {
        this.accessToken = requireNonNull(accessToken, "accessToken");
        this.expiresIn = expiresIn;
        this.refreshToken = requireNonNull(refreshToken, "refreshToken");
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
        return tokenType;
    }

    @JsonProperty("refresh_token")
    public String refreshToken() {
        return refreshToken;
    }
}
