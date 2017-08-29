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

import com.fasterxml.jackson.databind.JsonNode;

public enum ChangeType {
    /**
     * Adds a new text file or replaces an existing file.
     */
    UPSERT_JSON(JsonNode.class),

    /**
     * Adds a new text file or replaces an existing file.
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
     * <a href="https://www.gnu.org/software/diffutils/manual/html_node/Unified-Format.html">unified format
     * string</a>.
     */
    APPLY_TEXT_PATCH(String.class);

    private final Class<?> contentType;

    ChangeType(Class<?> contentType) {
        this.contentType = contentType;
    }

    public Class<?> contentType() {
        return contentType;
    }
}
