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

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A session master key used to derive session keys.
 */
public final class SessionMasterKey {

    private final byte[] wrappedMasterKey;
    private final byte[] salt;
    private final int version;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public SessionMasterKey(@JsonProperty("wrappedMasterKey") byte[] wrappedMasterKey,
                            @JsonProperty("salt") byte[] salt,
                            @JsonProperty("version") int version) {
        this.wrappedMasterKey = requireNonNull(wrappedMasterKey, "wrappedMasterKey");
        this.salt = requireNonNull(salt, "salt");
        this.version = version;
    }

    /**
     * Returns a wrapped session master key.
     */
    @JsonProperty
    public byte[] wrappedMasterKey() {
        return wrappedMasterKey;
    }

    /**
     * Returns a salt used to derive session keys from the master key.
     */
    @JsonProperty
    public byte[] salt() {
        return salt;
    }

    /**
     * Returns the version of this session master key.
     */
    @JsonProperty
    public int version() {
        return version;
    }

    @Override
    public String toString() {
        return "SessionMasterKey{version=" + version + '}';
    }
}
