/*
 * Copyright 2025 LINE Corporation
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

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A session master key used to derive session keys.
 */
public final class SessionMasterKey {

    private final String wrappedMasterKey;
    private final int version;
    private final String salt;
    private final String kekId;
    private final String creation;
    private final Instant creationInstant;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public SessionMasterKey(@JsonProperty("wrappedMasterKey") String wrappedMasterKey,
                            @JsonProperty("version") int version,
                            @JsonProperty("salt") String salt,
                            @JsonProperty("kekId") String kekId,
                            @JsonProperty("creation") Instant creationInstant) {
        this.wrappedMasterKey = requireNonNull(wrappedMasterKey, "wrappedMasterKey");
        checkArgument(version > 0, "version must be positive: %s", version);
        this.version = version;
        this.salt = requireNonNull(salt, "salt");
        this.kekId = requireNonNull(kekId, "kekId");
        this.creationInstant = creationInstant;
        creation = ISO_INSTANT.format(requireNonNull(creationInstant, "creation"));
    }

    /**
     * Returns a wrapped session master key.
     */
    @JsonProperty
    public String wrappedMasterKey() {
        return wrappedMasterKey;
    }

    /**
     * Returns the version of the session master key.
     */
    @JsonProperty
    public int version() {
        return version;
    }

    /**
     * Returns a salt used to derive session keys from the master key. It's encoded in base64.
     */
    @JsonProperty
    public String salt() {
        return salt;
    }

    /**
     * Returns the key encryption key (KEK) ID used to wrap the session master key.
     */
    @JsonProperty
    public String kekId() {
        return kekId;
    }

    /**
     * Returns the creation timestamp of the session master key.
     */
    @JsonProperty
    public String creation() {
        return creation;
    }

    /**
     * Returns the creation instant of the session master key.
     */
    public Instant creationInstant() {
        return creationInstant;
    }

    @Override
    public String toString() {
        return toStringHelper(this).add("wrappedMasterKey", "****")
                                   .add("version", version)
                                   .add("salt", "****")
                                   .add("kekId", kekId)
                                   .add("creation", creation)
                                   .toString();
    }
}
