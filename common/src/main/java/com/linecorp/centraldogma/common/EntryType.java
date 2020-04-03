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

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Ascii;

/**
 * The type of an {@link Entry}.
 */
public enum EntryType {
    /**
     * A UTF-8 encoded JSON file.
     */
    JSON(JsonNode.class),
    /**
     * A UTF-8 encoded text file.
     */
    TEXT(String.class),
    /**
     * A directory.
     */
    DIRECTORY(Void.class),
    /**
     * An unknown type. This is used for the {@link Query#contentType()} when the {@link Query} is created
     * using {@link Query#of(QueryType, String, String...)}.
     */
    UNKNOWN(null);

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

    @Nullable
    private final Class<?> type;

    EntryType(@Nullable Class<?> type) {
        this.type = type;
    }

    /**
     * Returns the type of the content returned by {@link Entry#content()}.
     */
    @Nullable
    public Class<?> type() {
        return type;
    }
}
