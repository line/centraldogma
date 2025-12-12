/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import static com.linecorp.centraldogma.internal.Json5.isJson5;
import static com.linecorp.centraldogma.internal.Util.validateJsonFilePath;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Json5;

final class JsonChange extends AbstractChange<JsonNode> {

    private final JsonNode jsonNode;
    @Nullable
    private final String jsonText;

    @Nullable
    private String contentAsText;

    JsonChange(String path, ChangeType type, JsonNode jsonNode) {
        this(path, type, jsonNode, null);
    }

    JsonChange(String path, ChangeType type, String jsonText) {
        this(path, type, null, jsonText);
    }

    private JsonChange(String path, ChangeType type, @Nullable JsonNode jsonNode, @Nullable String jsonText) {
        super(path, type);
        checkArgument(type.contentType() == JsonNode.class, "type.contentType() must be JsonNode.class");
        validateJsonFilePath(path, "path");

        if (jsonNode != null) {
            this.jsonNode = jsonNode;
            this.jsonText = null;
        } else {
            assert jsonText != null;
            try {
                if (isJson5(path)) {
                    this.jsonNode = Json5.readTree(jsonText);
                } else {
                    // Check if jsonText is a valid JSON.
                    this.jsonNode = Jackson.readTree(jsonText);
                }
            } catch (JsonProcessingException e) {
                throw new ChangeFormatException("failed to read a value as a JSON tree", e);
            }
            this.jsonText = jsonText;
        }
    }

    @Override
    public JsonNode content() {
        return jsonNode;
    }

    @Nullable
    @Override
    public String rawContent() {
        return jsonText;
    }

    @Override
    public String contentAsText() {
        if (jsonText != null) {
            return jsonText;
        }

        if (contentAsText != null) {
            return contentAsText;
        }
        try {
            return contentAsText = Jackson.writeValueAsString(jsonNode);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to convert JSON content to text: " + jsonNode, e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JsonChange)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final JsonChange that = (JsonChange) o;
        return Objects.equals(jsonNode, that.jsonNode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), jsonNode);
    }

    @Override
    public String toString() {
        String contentString;
        if (jsonText != null) {
            contentString = jsonText;
        } else {
            contentString = jsonNode.toString();
        }
        if (contentString.length() > 128) {
            contentString = contentString.substring(0, 128) + "...(length: " + contentString.length() + ')';
        }
        final ToStringHelper builder = MoreObjects.toStringHelper(this)
                                                  .add("type", type())
                                                  .add("path", path());
        if (jsonText != null) {
            builder.add("rawContent", contentString);
        } else {
            builder.add("content", contentString);
        }
        return builder.toString();
    }
}
