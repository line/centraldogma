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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Ascii;

/**
 * The type of a {@link Change}.
 */
public enum ChangeType {
    /**
     * Adds a new JSON file or replaces an existing file. {@link Change#content()} will return
     * the {@link JsonNode} that represents the content of the file.
     */
    UPSERT_JSON(JsonNode.class),

    /**
     * Adds a new text file or replaces an existing file. {@link Change#content()} will return
     * the {@link String} that represents the content of the file.
     */
    UPSERT_TEXT(String.class),

    /**
     * Removes an existing file. The {@link Change#content()} of this type is always {@code null}.
     */
    REMOVE(Void.class),

    /**
     * Renames an existing file. The {@link Change#content()} of this type is the new path of the renamed file.
     */
    RENAME(String.class),

    /**
     * Applies a JSON patch to a JSON file. The {@link Change#content()} of this type is a JSON patch object,
     * as defined in <a href="https://tools.ietf.org/html/rfc6902">RFC 6902</a>.
     */
    APPLY_JSON_PATCH(JsonNode.class),

    /**
     * Applies a textual patch to a text file. The {@link Change#content()} of this type is a
     * <a href="https://en.wikipedia.org/wiki/Diff_utility#Unified_format">unified format</a> string.
     */
    APPLY_TEXT_PATCH(String.class);

    private final Class<?> contentType;

    ChangeType(Class<?> contentType) {
        this.contentType = contentType;
    }

    /**
     * Returns the type of the content returned by {@link Change#content()}.
     */
    public Class<?> contentType() {
        return contentType;
    }

    /**
     * Returns a {@link ChangeType} from the specified {@code value} case-insensitively.
     */
    public static ChangeType parse(String value) {
        requireNonNull(value, "value");
        checkArgument(!value.isEmpty(), "the value for ChangeType should not be empty.");

        return ChangeType.valueOf(Ascii.toUpperCase(value));
    }
}
