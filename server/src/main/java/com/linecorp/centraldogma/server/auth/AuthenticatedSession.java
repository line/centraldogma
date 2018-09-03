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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.internal.Util;

/**
 * An authenticated session which can be replicated to the other Central Dogma replicas as a serialized form.
 */
public final class AuthenticatedSession implements Serializable {

    private static final long serialVersionUID = 4253152956820423809L;

    private final String id;
    private final String username;
    private final Instant creationTime;
    private final Instant expirationTime;
    @Nullable
    private final Serializable rawSession;

    /**
     * Creates a new {@link AuthenticatedSession} instance.
     *
     * @param id the session ID
     * @param username the name of the user which belongs to this session
     * @param sessionValidDuration the {@link Duration} that this session is valid
     */
    public static AuthenticatedSession of(String id, String username, Duration sessionValidDuration) {
        requireNonNull(id, "id");
        requireNonNull(username, "username");
        requireNonNull(sessionValidDuration, "sessionValidDuration");

        final Instant now = Instant.now();
        final Instant expirationTime = now.plus(sessionValidDuration);
        return new AuthenticatedSession(id, username, now, expirationTime, null);
    }

    /**
     * Creates a new {@link AuthenticatedSession} instance.
     *
     * @param id the session ID
     * @param username the name of the user which belongs to this session
     * @param creationTime the created time {@link Instant}
     * @param expirationTime the time {@link Instant} that this session is to be expired at
     * @param rawSession the serializable session object which is specific to authentication provider
     */
    @JsonCreator
    public AuthenticatedSession(@JsonProperty("id") String id,
                                @JsonProperty("username") String username,
                                @JsonProperty("creationTime") Instant creationTime,
                                @JsonProperty("expirationTime") Instant expirationTime,
                                @JsonProperty("rawSession") @Nullable Serializable rawSession) {
        this.id = requireNonNull(id, "id");
        this.username = requireNonNull(username, "username");
        this.creationTime = requireNonNull(creationTime, "creationTime");
        this.expirationTime = requireNonNull(expirationTime, "expirationTime");
        this.rawSession = rawSession;
    }

    /**
     * Returns the session ID.
     */
    @JsonProperty
    public String id() {
        return id;
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

    /**
     * Returns a raw session instance.
     */
    @Nullable
    @JsonProperty
    public Serializable rawSession() {
        return rawSession;
    }

    /**
     * Returns a raw session instance which is casted to {@code T} type.
     *
     * @throws NullPointerException if the {@code rawSession} is {@code null}
     * @throws ClassCastException if the {@code rawSession} cannot be casted to {@code T}
     */
    <T> T castedRawSession() {
        return Util.unsafeCast(requireNonNull(rawSession, "rawSession"));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("id", id)
                          .add("username", username)
                          .add("creationTime", creationTime)
                          .add("expirationTime", expirationTime)
                          .add("rawSession",
                               rawSession != null ? rawSession.getClass().getSimpleName() : null)
                          .toString();
    }
}
