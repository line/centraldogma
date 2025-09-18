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
package com.linecorp.centraldogma.server.auth;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

/**
 * An authenticated session which can be replicated to the other Central Dogma replicas as a serialized form.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Session {

    private final String id;
    @Nullable
    private final String csrfToken;
    private final String username;
    private final Instant creationTime;
    private final Instant expirationTime;

    /**
     * Creates a new {@link Session} instance.
     *
     * @param id the session ID
     * @param username the name of the user which belongs to this session
     * @param sessionValidDuration the {@link Duration} that this session is valid
     */
    public Session(String id, String username, Duration sessionValidDuration) {
        this.id = requireNonNull(id, "id");
        this.username = requireNonNull(username, "username");
        creationTime = Instant.now();
        expirationTime = creationTime.plus(requireNonNull(sessionValidDuration, "sessionValidDuration"));
        csrfToken = null;
    }

    /**
     * Creates a new {@link Session} instance.
     *
     * @param id the session ID
     * @param username the name of the user which belongs to this session
     * @param creationTime the created time {@link Instant}
     * @param expirationTime the time {@link Instant} that this session is to be expired at
     */
    @JsonCreator
    public Session(@JsonProperty("id") String id,
                   @JsonProperty("csrfToken") @Nullable String csrfToken,
                   @JsonProperty("username") String username,
                   @JsonProperty("creationTime") Instant creationTime,
                   @JsonProperty("expirationTime") Instant expirationTime,
                   @JsonProperty("rawSession")
                   @JsonDeserialize(using = RawSessionJsonDeserializer.class)
                   @Nullable Serializable unused) {
        this.id = requireNonNull(id, "id");
        this.csrfToken = csrfToken;
        this.username = requireNonNull(username, "username");
        this.creationTime = requireNonNull(creationTime, "creationTime");
        this.expirationTime = requireNonNull(expirationTime, "expirationTime");
    }

    /**
     * Returns the session ID.
     */
    @JsonProperty
    public String id() {
        return id;
    }

    /**
     * Returns the CSRF token.
     */
    @JsonProperty
    @Nullable
    public String csrfToken() {
        return csrfToken;
    }

    /**
     * Returns the name of the user which belongs to this session.
     */
    @JsonProperty
    public String username() {
        return username;
    }

    /**
     * Returns the created time {@link Instant}.
     */
    @JsonProperty
    public Instant creationTime() {
        return creationTime;
    }

    /**
     * Returns the time {@link Instant} that this session is to be expired at.
     */
    @JsonProperty
    public Instant expirationTime() {
        return expirationTime;
    }

    @Override
    public String toString() {
        final ToStringHelper toStringHelper = MoreObjects.toStringHelper(this)
                                                         .add("id", id)
                                                         .add("username", username)
                                                         .add("creationTime", creationTime)
                                                         .add("expirationTime", expirationTime);
        if (csrfToken != null) {
            toStringHelper.add("csrfToken", "****");
        }
        return toStringHelper.toString();
    }
}
