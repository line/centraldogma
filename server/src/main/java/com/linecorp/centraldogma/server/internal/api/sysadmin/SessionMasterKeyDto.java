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
package com.linecorp.centraldogma.server.internal.api.sysadmin;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data transfer object (DTO) for a session master key.
 * Sensitive information such as the key material or salt is not included.
 */
public final class SessionMasterKeyDto {

    private final int version;
    private final String kekId;
    private final String creation;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public SessionMasterKeyDto(@JsonProperty("version") int version,
                               @JsonProperty("kekId") String kekId,
                               @JsonProperty("creation") String creation) {
        checkArgument(version > 0, "version must be positive: %s", version);
        this.version = version;
        this.kekId = requireNonNull(kekId, "kekId");
        this.creation = requireNonNull(creation, "creation");
    }

    /**
     * Returns the version of the session master key.
     */
    @JsonProperty
    public int version() {
        return version;
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

    @Override
    public String toString() {
        return toStringHelper(this).add("version", version)
                                   .add("kekId", kekId)
                                   .add("creation", creation)
                                   .toString();
    }
}
