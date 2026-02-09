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

package com.linecorp.centraldogma.common;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Yaml;

/**
 * A holder which has the content and its {@link EntryType}.
 *
 * @param <T> the type of the content
 */
public interface ContentHolder<T> {

    /**
     * Returns the {@link EntryType} of the content.
     */
    EntryType type();

    /**
     * Returns the content.
     *
     * @throws IllegalStateException if the content is {@code null}
     */
    T content();

    /**
     * Returns the textual representation of the specified content.
     *
     * @throws IllegalStateException if the content is {@code null}
     */
    default String contentAsText() {
        final T content = content();
        if (content instanceof JsonNode) {
            try {
                if (type() == EntryType.YAML) {
                    return Yaml.writeValueAsString(content);
                } else {
                    return Jackson.writeValueAsString(content);
                }
            } catch (JsonProcessingException e) {
                // Should never happen because it's a JSON tree already.
                throw new Error(e);
            }
        } else {
            return content.toString();
        }
    }

    /**
     * Returns the prettified textual representation of the specified content. Only a {@link TreeNode} is
     * prettified currently.
     *
     * @throws IllegalStateException if the content is {@code null}
     */
    default String contentAsPrettyText() {
        final T content = content();
        if (content instanceof TreeNode) {
            try {
                if (type() == EntryType.YAML) {
                    return Yaml.writeValueAsString(content);
                } else {
                    return Jackson.writeValueAsPrettyString(content);
                }
            } catch (JsonProcessingException e) {
                // Should never happen because it's a JSON tree already.
                throw new Error(e);
            }
        } else {
            return content.toString();
        }
    }

    /**
     * Returns the JSON representation of the specified content.
     *
     * @return the {@link JsonNode} parsed from the content
     *
     * @throws IllegalStateException if the content is {@code null}
     * @throws JsonParseException if failed to parse the content as JSON
     */
    default JsonNode contentAsJson() throws JsonParseException {
        final T content = content();
        if (content instanceof JsonNode) {
            return (JsonNode) content;
        }

        try {
            if (type() == EntryType.YAML) {
                return Yaml.readTree(contentAsText());
            } else {
                return Jackson.readTree(contentAsText());
            }
        } catch (JsonParseException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new JsonParseException(null, "Failed to parse the content", e);
        }
    }

    /**
     * Returns the value converted from the JSON representation of the specified content.
     *
     * @return the value converted from the content
     *
     * @throws IllegalStateException if the content is {@code null}
     * @throws JsonParseException if failed to parse the content as JSON
     * @throws JsonMappingException if failed to convert the parsed JSON into {@code valueType}
     */
    default <U> U contentAsJson(Class<U> valueType) throws JsonParseException, JsonMappingException {
        return Jackson.treeToValue(contentAsJson(), valueType);
    }
}
