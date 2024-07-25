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

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.centraldogma.internal.Jackson;

/**
 * A {@link StdDeserializer} that deserializes a {@link PluginConfig}.
 */
public final class PluginConfigDeserializer extends StdDeserializer<PluginConfig> {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new instance.
     */
    public PluginConfigDeserializer() {
        super(PluginConfig.class);
    }

    @Override
    public PluginConfig deserialize(JsonParser jp, DeserializationContext ctxt)
            throws IOException {
        final JsonNode jsonNode = jp.readValueAsTree();
        final JsonNode configType = jsonNode.get("configType");
        if (configType == null || configType.asText() == null) {
            ctxt.reportInputMismatch(PluginConfig.class, "plugin config should have a type.");
            // should never reach here
            throw new Error();
        }

        final String configTypeText = configType.asText();
        try {
            final Class<?> clazz = Class.forName(configTypeText);
            if (!PluginConfig.class.isAssignableFrom(clazz)) {
                ctxt.reportInputMismatch(PluginConfig.class, configTypeText + " is not a subtype of " +
                                                             PluginConfig.class.getSimpleName());
                // should never reach here
                throw new Error();
            }

            assert jsonNode instanceof ObjectNode;
            final ObjectNode objectNode = (ObjectNode) jsonNode;
            objectNode.remove("configType");

            return (PluginConfig) Jackson.treeToValue(objectNode, clazz);
        } catch (ClassNotFoundException e) {
            ctxt.reportInputMismatch(PluginConfig.class, configTypeText + " is not found.");
            // should never reach here
            throw new Error();
        }
    }
}
