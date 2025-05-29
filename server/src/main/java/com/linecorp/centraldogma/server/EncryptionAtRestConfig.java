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
package com.linecorp.centraldogma.server;

import static com.google.common.base.MoreObjects.firstNonNull;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * Encryption at Rest configuration.
 */
public final class EncryptionAtRestConfig {

    private final boolean enabled;

    /**
     * Creates an instance.
     */
    @JsonCreator
    public EncryptionAtRestConfig(@JsonProperty("enabled") @Nullable Boolean enabled) {
        this.enabled = firstNonNull(enabled, false);
    }

    /**
     * Returns whether encryption at rest is enabled.
     */
    @JsonProperty
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("enabled", enabled)
                          .toString();
    }
}
