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

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.JacksonRequestConverterFunction;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeType;

/**
 * A request converter that converts to {@code Iterable<Change<?>>}.
 */
public final class ChangesRequestConverter extends JacksonRequestConverterFunction {

    @Override
    public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                 Class<?> expectedResultType) throws Exception {
        final JsonNode node = (JsonNode) super.convertRequest(ctx, request, JsonNode.class);
        if (node.get("changes") != null) {
            // have one entry or more than one entry
            final JsonNode changeNode = node.get("changes");

            final Builder<Change<?>> builder = ImmutableList.builder();
            for (JsonNode change : changeNode) {
                builder.add(readChange(change));
            }
            final ImmutableList<Change<?>> changes = builder.build();
            checkArgument(!changes.isEmpty(), "should have at least one change.");
            return changes;
        }

        // have only one entry
        return ImmutableList.of(readChange(node));
    }

    private static Change<?> readChange(JsonNode node) {
        checkArgument(node.get("path") != null && node.get("type") != null,
                      "a change should have a path and a type");
        final ChangeType changeType = ChangeType.parse(node.get("type").textValue());
        if (changeType != ChangeType.REMOVE) {
            checkArgument(node.get("content") != null, "a change should have a content.");
        }

        final String path = node.get("path").textValue();
        if (changeType == ChangeType.UPSERT_TEXT) {
            return Change.ofTextUpsert(path, node.get("content").textValue());
        }
        if (changeType == ChangeType.UPSERT_JSON) {
            return Change.ofJsonUpsert(path, node.get("content"));
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
