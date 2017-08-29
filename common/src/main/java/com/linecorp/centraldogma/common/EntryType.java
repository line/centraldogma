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

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Ascii;

public enum EntryType {
    JSON(JsonNode.class), TEXT(String.class), DIRECTORY(Void.class);

    /**
     * Guesses the {@link EntryType} from the specified {@code path}.
     */
    public static EntryType guessFromPath(String path) {
        requireNonNull(path, "path");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("empty path");
        }

        if (path.charAt(path.length() - 1) == '/') {
            return DIRECTORY;
        }

        if (Ascii.toLowerCase(path).endsWith(".json")) {
            return JSON;
        }

        return TEXT;
    }

    private final Class<?> type;

    EntryType(Class<?> type) {
        this.type = type;
    }

    public Class<?> type() {
        return type;
    }
}
