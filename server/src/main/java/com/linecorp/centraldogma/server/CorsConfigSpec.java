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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CORS configuration spec.
 */
public interface CorsConfigSpec {
    int DEFAULT_MAX_AGE = 7200;

    /**
     * Returns the list of origins which are allowed a CORS policy.
     */
    @JsonProperty
    List<String> allowedOrigins();

    /**
     * Returns how long in seconds the results of a preflight request can be cached.
     * If unspecified, the default of {@code 7200} seconds is returned.
     */
    @JsonProperty
    int maxAgeSeconds();
}
