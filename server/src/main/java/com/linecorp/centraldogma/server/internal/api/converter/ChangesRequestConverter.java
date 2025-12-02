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

import static com.google.common.base.Preconditions.checkArgument;

import java.lang.reflect.ParameterizedType;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeType;

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
            builder.add(readChange(change));
        }
        return builder.build();
    }

    private static Change<?> readChange(JsonNode node) {
        checkArgument(node.get("path") != null && node.get("type") != null,
                      "a change should have a path and a type");
        final ChangeType changeType = ChangeType.parse(node.get("type").textValue());
        if (changeType != ChangeType.REMOVE) {
            if (changeType == ChangeType.UPSERT_JSON) {
                checkArgument(node.get("content") != null || node.get("rawContent") != null,
                              "a change should have a content.");
            } else {
                checkArgument(node.get("content") != null, "a change should have a content.");
            }
        }

        final String path = node.get("path").textValue();
        if (changeType == ChangeType.UPSERT_TEXT) {
            return Change.ofTextUpsert(path, node.get("content").textValue());
        }
        if (changeType == ChangeType.UPSERT_JSON) {
            final JsonNode content = node.get("content");
            if (content != null) {
                return Change.ofJsonUpsert(path, content);
            }
            return Change.ofJsonUpsert(path, node.get("rawContent").textValue());
        }
        if (changeType == ChangeType.REMOVE) {
            return Change.ofRemoval(path);
        }
        if (changeType == ChangeType.RENAME) {
            return Change.ofRename(path, node.get("content").textValue());
        }
        if (changeType == ChangeType.APPLY_TEXT_PATCH) {
            return Change.ofTextPatch(path, node.get("content").textValue());
        }
        if (changeType == ChangeType.APPLY_JSON_PATCH) {
            return Change.ofJsonPatch(path, node.get("content"));
        }

        // Should never reach here.
        throw new Error();
    }
}
