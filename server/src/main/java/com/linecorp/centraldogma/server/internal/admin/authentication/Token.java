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

package com.linecorp.centraldogma.server.internal.admin.authentication;

import static java.util.Objects.requireNonNull;

import java.text.ParseException;
import java.text.ParsePosition;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.util.ISO8601Utils;
import com.google.common.base.MoreObjects;

@JsonInclude(Include.NON_NULL)
public final class Token {

    public static final Token EMPTY_TOKEN =
            new Token(null, null, null, null, null);

    private final String appId;
    private final String secret;
    private final User creator;
    private final Date creationTime;
    private String creationTimeAsText;

    @JsonCreator
    public Token(@JsonProperty("appId") String appId,
                 @JsonProperty("secret") String secret,
                 @JsonProperty("creator") User creator,
                 @JsonProperty("creationTime") String creationTimeAsText) throws ParseException {
        this(requireNonNull(appId, "appId"),
             requireNonNull(secret, "secret"),
             requireNonNull(creator, "creator"),
             ISO8601Utils.parse(requireNonNull(creationTimeAsText, "creationTimeAsText"),
                                new ParsePosition(0)),
             creationTimeAsText);
    }

    public Token(String appId, String secret, User creator, Date creationTime) {
        this(requireNonNull(appId, "appId"),
             requireNonNull(secret, "secret"),
             requireNonNull(creator, "creator"),
             requireNonNull(creationTime, "creationTime"),
             null);
    }

    private Token(String appId, String secret, User creator,
                  Date creationTime, String creationTimeAsText) {
        this.appId = appId;
        this.secret = secret;
        this.creator = creator;
        this.creationTime = creationTime;
        this.creationTimeAsText = creationTimeAsText;
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
    public User creator() {
        return creator;
    }

    @JsonProperty
    public String creationTime() {
        if (creationTimeAsText == null) {
            creationTimeAsText = ISO8601Utils.format(creationTime);
        }
        return creationTimeAsText;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("appId", appId())
                          .add("creator", creator())
                          .add("creationTime", creationTime())
                          .toString();
    }

    /**
     * Returns a new {@link Token} instance without its secret.
     */
    public Token withoutSecret() {
        return new Token(appId, null, creator, creationTime, creationTimeAsText);
    }
}
