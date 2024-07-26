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
package com.linecorp.centraldogma.xds.internal;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.centraldogma.server.plugin.AbstractPluginConfig;

/**
 * A plugin configuration for the control plane.
 */
public final class ControlPlanePluginConfig extends AbstractPluginConfig {

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public ControlPlanePluginConfig(@JsonProperty("enabled") @Nullable Boolean enabled) {
        super(enabled);
    }
}
