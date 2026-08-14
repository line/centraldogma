/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.api;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.MetadataPropertiesConfig;

/**
 * Annotated service object for retrieving the metadata properties configuration, so that clients such as
 * the web UI can render input forms for the declared properties.
 */
@ProducesJson
public final class MetadataPropertiesService {

    private final JsonNode metadataProperties;

    public MetadataPropertiesService(@Nullable MetadataPropertiesConfig config) {
        metadataProperties = config != null ? Jackson.valueToTree(config)
                                            : JsonNodeFactory.instance.objectNode();
    }

    /**
     * GET /metadataProperties
     *
     * <p>Returns the JSON Schemas of the additional metadata properties declared in the server
     * configuration, keyed by resource type. An empty object is returned if nothing is declared.
     */
    @Get("/metadataProperties")
    public JsonNode metadataProperties() {
        return metadataProperties;
    }
}
