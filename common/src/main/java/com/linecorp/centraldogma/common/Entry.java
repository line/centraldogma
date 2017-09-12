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

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.internal.Jackson;

public interface Entry<T> {

    static Entry<Void> rootDir() {
        return DefaultEntry.ROOT_DIR;
    }

    static Entry<String> ofText(String path, String content) {
        return new DefaultEntry<>(path, content, EntryType.TEXT);
    }

    static Entry<Void> ofDirectory(String path) {
        return new DefaultEntry<>(path, null, EntryType.DIRECTORY);
    }

    static Entry<JsonNode> ofJson(String path, JsonNode content) {
        return new JsonEntry(path, content);
    }

    static Entry<JsonNode> ofJson(String path, String content) throws IOException {
        return ofJson(path, Jackson.readTree(content));
    }

    static <T> Entry<T> of(String path, T content, EntryType type) {
        if (type == EntryType.JSON) {
            @SuppressWarnings("unchecked")
            final Entry<T> e = (Entry<T>) ofJson(path, (JsonNode) content);
            return e;
        }

        return new DefaultEntry<>(path, content, type);
    }

    EntryType type();

    String path();

    T content();

    String contentAsText();
}
