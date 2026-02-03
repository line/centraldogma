/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.common;

import static java.util.Objects.requireNonNull;

import java.io.IOException;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import com.linecorp.centraldogma.internal.Jackson;

final class ChangeDeserializer extends StdDeserializer<Change<?>> {

    private static final long serialVersionUID = -1811286391194654116L;

    ChangeDeserializer() {
        super(Change.class);
    }

    @Override
    public Change<?> deserialize(JsonParser jp, DeserializationContext ctxt)
            throws IOException {
        final ObjectCodec codec = jp.getCodec();
        final JsonNode root = codec.readTree(jp);

        final JsonNode typeNode = root.get("type");
        requireNonNull(typeNode, "type");
        final ChangeType type = codec.treeToValue(typeNode, ChangeType.class);

        final JsonNode pathNode = root.get("path");
        requireNonNull(pathNode, "path");
        final String path = pathNode.asText();

        final JsonNode content = root.get("content");
        final JsonNode rawContentNode = root.get("rawContent");
        final String rawContent;
        if (rawContentNode != null && !rawContentNode.isNull()) {
            rawContent = rawContentNode.asText();
        } else {
            rawContent = null;
        }
        return deserialize(type, path, content, rawContent);
    }

    private static Change<?> deserialize(ChangeType type, String path, @Nullable JsonNode content,
                                         @Nullable String rawContent) {
        requireNonNull(type, "type");
        final Class<?> contentType = type.contentType();
        if (contentType == Void.class) {
            if ((content != null && !content.isNull()) || rawContent != null) {
                return rejectIncompatibleContent(content, Void.class);
            }
        } else if (type.contentType() == String.class) {
            if (content != null && !content.isTextual()) {
                return rejectIncompatibleContent(content, String.class);
            }
        }

        if (type == ChangeType.REMOVE) {
            return Change.ofRemoval(path);
        }

        if (content == null && rawContent == null) {
            throw new IllegalArgumentException(
                    "content or rawContent is required for type: " + type);
        }
        if (content != null && rawContent != null) {
            throw new IllegalArgumentException(
                    "only one of content or rawContent must be set for type: " + type);
        }

        final Change<?> result;
        switch (type) {
            case UPSERT_JSON:
                // Raw content is only used for JSON_UPSERT for now.
                if (rawContent != null) {
                    result = Change.ofJsonUpsert(path, rawContent);
                } else {
                    result = Change.ofJsonUpsert(path, content);
                }
                break;
            case UPSERT_TEXT:
                if (rawContent != null) {
                    result = Change.ofTextUpsert(path, rawContent);
                } else {
                    result = Change.ofTextUpsert(path, content.asText());
                }
                break;
            case UPSERT_YAML:
                if (rawContent == null) {
                    throw new IllegalArgumentException("rawContent is required for YAML_UPSERT");
                }
                result = Change.ofYamlUpsert(path, rawContent);
                break;
            case RENAME:
                assert content != null;
                result = Change.ofRename(path, content.asText());
                break;
            case APPLY_JSON_PATCH:
                if (rawContent != null) {
                    try {
                        result = Change.ofJsonPatch(path, Jackson.readTree(rawContent));
                    } catch (JsonParseException e) {
                        throw new IllegalArgumentException("failed to parse JSON patch", e);
                    }
                } else {
                    result = Change.ofJsonPatch(path, content);
                }
                break;
            case APPLY_TEXT_PATCH:
                if (rawContent != null) {
                    result = Change.ofTextPatch(path, rawContent);
                } else {
                    result = Change.ofTextPatch(path, content.asText());
                }
                break;
            default:
                // Should never reach here
                throw new Error();
        }

        return result;
    }

    private static Change<?> rejectIncompatibleContent(@Nullable JsonNode content,
                                                       Class<?> contentType) {
        throw new IllegalArgumentException("incompatible content: " + content +
                                           " (expected: " + contentType.getName() + ')');
    }
}
