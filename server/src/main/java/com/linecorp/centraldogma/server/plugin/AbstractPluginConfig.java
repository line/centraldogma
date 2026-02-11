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
package com.linecorp.centraldogma.server.plugin;

import static com.google.common.base.MoreObjects.firstNonNull;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An abstract {@link PluginConfig} implementation.
 */
public abstract class AbstractPluginConfig implements PluginConfig {

    private final boolean enabled;

    /**
     * Creates a new instance.
     */
    protected AbstractPluginConfig(@Nullable Boolean enabled) {
        this.enabled = firstNonNull(enabled, true);
    }

    /**
     * Returns whether the plugin is enabled.
     */
    @JsonProperty
    @Override
    public boolean enabled() {
        return enabled;
    }
}
