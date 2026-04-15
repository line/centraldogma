/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.api.converter;

import static com.linecorp.centraldogma.internal.Json5.isJsonCompatible;
import static com.linecorp.centraldogma.internal.Yaml.isYaml;

import java.lang.reflect.ParameterizedType;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeFormatException;
import com.linecorp.centraldogma.common.ChangeType;
import com.linecorp.centraldogma.internal.Jackson;

/**
 * A request converter that converts to {@code Iterable<Change<?>>}.
 */
public final class ChangesRequestConverter implements RequestConverterFunction {

    private final JacksonRequestConverterFunction delegate = new JacksonRequestConverterFunction();

    @Override
    public List<Change<?>> convertRequest(
            ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
            @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {

        final JsonNode node = (JsonNode) delegate.convertRequest(ctx, request, JsonNode.class, null);
        if (node == null) {
            return RequestConverterFunction.fallthrough();
        }

        final ArrayNode changesNode;
        if (node.getNodeType() == JsonNodeType.ARRAY) {
            changesNode = (ArrayNode) node;
        } else {
            final JsonNode maybeChangesNode = node.get("changes");
            if (maybeChangesNode != null) {
                if (maybeChangesNode.getNodeType() == JsonNodeType.ARRAY) {
                    changesNode = (ArrayNode) maybeChangesNode;
                } else {
                    throw new IllegalArgumentException("'changes' must be an array.");
                }
            } else {
                // have only one entry
                return ImmutableList.of(readChange(node));
            }
        }

        final ImmutableList.Builder<Change<?>> builder = ImmutableList.builder();
        for (JsonNode change : changesNode) {
            final Change<?> change1 = readChange(change);
            final String path = change1.path();
            if (change1.type() == ChangeType.UPSERT_TEXT && (isJsonCompatible(path) || isYaml(path))) {
                try {
                    final String content = change1.contentAsText();
                    assert content != null;
                    Jackson.readTree(path, content);
                } catch (JsonProcessingException e) {
                    throw new ChangeFormatException(
                            "failed to read a value as a " +
                            (isYaml(path) ? "YAML" : "JSON") + " tree. file: " + path, e);
                }
            }

            builder.add(change1);
        }
        return builder.build();
    }

    private static Change<?> readChange(JsonNode node) {
        try {
            return Jackson.treeToValue(node, Change.class);
        } catch (JsonParseException | JsonMappingException e) {
            throw new IllegalArgumentException("Failed to parse a change", e);
        }
    }
}
