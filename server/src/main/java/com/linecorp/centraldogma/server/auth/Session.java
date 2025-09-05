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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * An authenticated session which can be replicated to the other Central Dogma replicas as a serialized form.
 */
public final class Session {

    private final String id;
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
    public Session(String id, String csrfToken, String username, Duration sessionValidDuration) {
        this.id = requireNonNull(id, "id");
        this.csrfToken = requireNonNull(csrfToken, "csrfToken");
        this.username = requireNonNull(username, "username");
        creationTime = Instant.now();
        expirationTime = creationTime.plus(requireNonNull(sessionValidDuration, "sessionValidDuration"));
    }

    /**
     * Creates a new {@link Session} instance.
     *
     * @param id the session ID
     * @param csrfToken the CSRF token
     * @param username the name of the user which belongs to this session
     * @param creationTime the created time {@link Instant}
     * @param expirationTime the time {@link Instant} that this session is to be expired at
     */
    @JsonCreator
    public Session(@JsonProperty("id") String id,
                   @JsonProperty("csrfToken") String csrfToken,
                   @JsonProperty("username") String username,
                   @JsonProperty("creationTime") Instant creationTime,
                   @JsonProperty("expirationTime") Instant expirationTime) {
        this.id = requireNonNull(id, "id");
        this.csrfToken = requireNonNull(csrfToken, "csrfToken");
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
    public int hashCode() {
        return Objects.hash(id, csrfToken, username, creationTime, expirationTime);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Session)) {
            return false;
        }
        final Session that = (Session) obj;
        return id.equals(that.id) &&
               csrfToken.equals(that.csrfToken) &&
               username.equals(that.username) &&
               creationTime.equals(that.creationTime) &&
               expirationTime.equals(that.expirationTime);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("id", "****")
                          .add("csrfToken", "****")
                          .add("username", username)
                          .add("creationTime", creationTime)
                          .add("expirationTime", expirationTime)
                          .toString();
    }
}
