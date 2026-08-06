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

package com.linecorp.centraldogma.server.internal.metadata;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion.VersionFlag;
import com.networknt.schema.ValidationMessage;

import com.linecorp.centraldogma.server.MetadataPropertiesConfig;

/**
 * Validates the {@code properties} of a resource against the JSON Schemas declared in
 * {@link MetadataPropertiesConfig}. When a schema declares a top-level {@code properties} keyword,
 * properties that are not declared in it are silently dropped so that a new property can be declared
 * with a rolling restart. When a schema declares its shape in another way (e.g. {@code $ref} or
 * {@code allOf}), nothing is dropped and the whole object is validated as is.
 */
public final class MetadataPropertiesValidator {

    /**
     * The type of a resource that can have metadata properties.
     */
    public enum ResourceType {
        PROJECT("project"),
        REPO("repo"),
        APP_IDENTITY("appIdentity");

        private final String key;

        ResourceType(String key) {
            this.key = key;
        }
    }

    private final Map<ResourceType, JsonSchema> schemas;
    // The top-level declared property names. An entry is absent when the schema declares no top-level
    // "properties" keyword, in which case nothing is filtered.
    private final Map<ResourceType, Set<String>> declaredProperties;

    public MetadataPropertiesValidator(@Nullable MetadataPropertiesConfig config) {
        if (config == null) {
            schemas = ImmutableMap.of();
            declaredProperties = ImmutableMap.of();
            return;
        }
        final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(VersionFlag.V202012);
        final ImmutableMap.Builder<ResourceType, JsonSchema> schemas = ImmutableMap.builder();
        final ImmutableMap.Builder<ResourceType, Set<String>> declaredProperties = ImmutableMap.builder();
        compile(factory, ResourceType.PROJECT, config.project(), schemas, declaredProperties);
        compile(factory, ResourceType.REPO, config.repo(), schemas, declaredProperties);
        compile(factory, ResourceType.APP_IDENTITY, config.appIdentity(), schemas, declaredProperties);
        this.schemas = schemas.build();
        this.declaredProperties = declaredProperties.build();
    }

    private static void compile(JsonSchemaFactory factory, ResourceType type, @Nullable JsonNode schemaNode,
                                ImmutableMap.Builder<ResourceType, JsonSchema> schemas,
                                ImmutableMap.Builder<ResourceType, Set<String>> declaredProperties) {
        if (schemaNode == null) {
            return;
        }
        final JsonSchema schema;
        try {
            schema = factory.getSchema(schemaNode);
            schema.initializeValidators();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Invalid JSON Schema in metadataProperties." + type.key + ": " + schemaNode, e);
        }
        schemas.put(type, schema);
        final JsonNode propertiesNode = schemaNode.get("properties");
        if (propertiesNode != null && propertiesNode.isObject()) {
            final ImmutableSet.Builder<String> names = ImmutableSet.builder();
            propertiesNode.fieldNames().forEachRemaining(names::add);
            declaredProperties.put(type, names.build());
        }
    }

    /**
     * Validates the specified {@code properties} against the schema declared for the {@link ResourceType}
     * and returns the properties to store. Undeclared properties are dropped rather than rejected.
     * {@code null} is returned if there is nothing to store.
     *
     * @throws IllegalArgumentException if the declared properties do not conform to the schema
     */
    @Nullable
    public JsonNode validate(ResourceType type, @Nullable JsonNode properties) {
        if (properties != null && properties.isNull()) {
            // An explicit JSON null is equivalent to an absent field.
            properties = null;
        }
        final JsonSchema schema = schemas.get(type);
        if (schema == null) {
            // No schema is declared for the resource type; ignore all properties.
            return null;
        }
        if (properties != null && !properties.isObject()) {
            throw new IllegalArgumentException(
                    "properties must be a JSON object: " + properties.getNodeType());
        }

        final Set<String> declared = declaredProperties.get(type);
        final ObjectNode filtered;
        if (properties == null) {
            filtered = JsonNodeFactory.instance.objectNode();
        } else if (declared == null) {
            // The schema declares no top-level "properties" keyword; validate the object as is.
            filtered = properties.deepCopy();
        } else {
            filtered = JsonNodeFactory.instance.objectNode();
            for (String name : declared) {
                final JsonNode value = properties.get(name);
                if (value != null) {
                    filtered.set(name, value);
                }
            }
        }

        final Set<ValidationMessage> messages = schema.validate(filtered);
        if (!messages.isEmpty()) {
            throw new IllegalArgumentException(
                    "properties do not conform to the schema of metadataProperties." + type.key + ": " +
                    messages.stream().map(ValidationMessage::getMessage)
                            .collect(Collectors.joining(", ")));
        }
        return filtered.isEmpty() ? null : filtered;
    }
}
