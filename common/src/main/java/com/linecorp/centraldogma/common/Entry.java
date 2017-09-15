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

import java.io.IOException;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.internal.Jackson;

/**
 * A file or a directory in a repository.
 *
 * @param <T> the content type. {@link JsonNode} if JSON. {@link String} if text.
 */
public interface Entry<T> {

    /**
     * Returns an {@link Entry} of a root directory. This method is similar to {@code ofDirectory("/")}
     * except that this method returns a singleton.
     */
    static Entry<Void> rootDir() {
        return DefaultEntry.ROOT_DIR;
    }

    /**
     * Returns a newly-created {@link Entry} of a directory.
     * @param path the path of the directory
     */
    static Entry<Void> ofDirectory(String path) {
        return new DefaultEntry<>(path, null, EntryType.DIRECTORY);
    }

    /**
     * Returns a newly-created {@link Entry} of a JSON file.
     *
     * @param path the path of the JSON file
     * @param content the content of the JSON file
     */
    static Entry<JsonNode> ofJson(String path, JsonNode content) {
        return new JsonEntry(path, content);
    }

    /**
     * Returns a newly-created {@link Entry} of a JSON file.
     *
     * @param path the path of the JSON file
     * @param content the content of the JSON file
     *
     * @throws IOException if the {@code content} is not a valid JSON
     */
    static Entry<JsonNode> ofJson(String path, String content) throws IOException {
        return ofJson(path, Jackson.readTree(content));
    }

    /**
     * Returns a newly-created {@link Entry} of a text file.
     *
     * @param path the path of the text file
     * @param content the content of the text file
     */
    static Entry<String> ofText(String path, String content) {
        return new DefaultEntry<>(path, content, EntryType.TEXT);
    }

    /**
     * Returns a newly-created {@link Entry}.
     *
     * @param path the path of the {@link Entry}
     * @param content the content of the {@link Entry}
     * @param type the type of the {@link Entry}
     * @param <T> the content type. {@link JsonNode} if JSON. {@link String} if text.
     */
    static <T> Entry<T> of(String path, T content, EntryType type) {
        if (type == EntryType.JSON) {
            @SuppressWarnings("unchecked")
            final Entry<T> e = (Entry<T>) ofJson(path, (JsonNode) content);
            return e;
        }

        return new DefaultEntry<>(path, content, type);
    }

    /**
     * Returns the type of this {@link Entry}.
     */
    EntryType type();

    /**
     * Returns the path of this {@link Entry}.
     */
    String path();

    /**
     * Returns the content of this {@link Entry}.
     *
     * @return the content if this {@link Entry} is not a directory. {@code null} if directory.
     */
    @Nullable
    T content();

    /**
     * Returns the textual representation of {@link #content()}.
     *
     * @return the textual representation of {@link #content()} if this {@link Entry} is not a directory.
     *         {@code null} if directory.
     */
    @Nullable
    String contentAsText();
}
