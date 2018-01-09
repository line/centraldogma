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

import static com.linecorp.centraldogma.internal.Util.validateFilePath;
import static com.linecorp.centraldogma.internal.Util.validateJsonFilePath;
import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.internal.Jackson;

final class DefaultChange<T> implements Change<T> {

    @JsonCreator
    static DefaultChange<?> deserialize(@JsonProperty("type") ChangeType type,
                                        @JsonProperty("path") String path,
                                        @JsonProperty("content") JsonNode content) {
        requireNonNull(type, "type");
        final Class<?> contentType = type.contentType();
        if (contentType == Void.class) {
            if (content != null && !content.isNull()) {
                return rejectIncompatibleContent(content, Void.class);
            }
        } else if (type.contentType() == String.class) {
            if (content == null || !content.isTextual()) {
                return rejectIncompatibleContent(content, String.class);
            }
        }

        final Change<?> result;

        switch (type) {
            case UPSERT_JSON:
                result = Change.ofJsonUpsert(path, content);
                break;
            case UPSERT_TEXT:
                result = Change.ofTextUpsert(path, content.asText());
                break;
            case REMOVE:
                result = Change.ofRemoval(path);
                break;
            case RENAME:
                result = Change.ofRename(path, content.asText());
                break;
            case APPLY_JSON_PATCH:
                result = Change.ofJsonPatch(path, content);
                break;
            case APPLY_TEXT_PATCH:
                result = Change.ofTextPatch(path, content.asText());
                break;
            default:
                // Should never reach here
                throw new Error();
        }

        // Ugly downcast, but otherwise we would have needed to write a custom serializer.
        return (DefaultChange<?>) result;
    }

    private static DefaultChange<?> rejectIncompatibleContent(JsonNode content, Class<?> contentType) {
        throw new IllegalArgumentException("incompatible content: " + content +
                                           " (expected: " + contentType.getName() + ')');
    }

    private final String path;
    private final ChangeType type;
    private final T content;

    DefaultChange(String path, ChangeType type, T content) {
        this.type = requireNonNull(type, "type");

        if (type.contentType() == JsonNode.class) {
            validateJsonFilePath(path, "path");
        } else {
            validateFilePath(path, "path");
        }

        this.path = path;
        this.content = content;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public ChangeType type() {
        return type;
    }

    @Override
    public T content() {
        return content;
    }

    @Override
    public String contentAsText() {
        if (content == null) {
            return null;
        }

        if (content instanceof CharSequence) {
            return content.toString();
        }

        if (content instanceof JsonNode) {
            try {
                return Jackson.writeValueAsString(content);
            } catch (JsonProcessingException e) {
                // Should never reach here.
                throw new Error(e);
            }
        }

        throw new Error();
    }

    @Override
    public int hashCode() {
        return type.hashCode() * 31 + path.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Change)) {
            return false;
        }

        final Change<?> that = (Change<?>) o;
        if (type != that.type()) {
            return false;
        }

        if (!path.equals(that.path())) {
            return false;
        }

        return Objects.equals(content, that.content());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(Change.class)
                          .add("type", type)
                          .add("path", path)
                          .toString();
    }
}
