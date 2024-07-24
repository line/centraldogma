/*
 * Copyright 2024 LINE Corporation
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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A configuration of a plugin.
 */
public final class PluginConfig {

    private final String name;
    private final boolean enabled;
    @Nullable
    private final JsonNode config;

    /**
     * Creates a new instance.
     */
    public PluginConfig(@JsonProperty(value = "name", required = true) String name,
                        @JsonProperty("enabled") @Nullable Boolean enabled,
                        @JsonProperty("config") @Nullable JsonNode config) {
        this.name = name;
        this.enabled = firstNonNull(enabled, true);
        this.config = config;
    }

    /**
     * Returns the name of the plugin.
     */
    @JsonProperty
    public String name() {
        return name;
    }

    /**
     * Returns whether the plugin is enabled.
     */
    @JsonProperty
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the configuration of the plugin.
     */
    @JsonProperty
    @Nullable
    public JsonNode config() {
        return config;
    }
}
