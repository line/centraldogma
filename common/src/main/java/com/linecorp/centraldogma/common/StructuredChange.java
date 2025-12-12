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
import static com.linecorp.centraldogma.internal.Util.isValidJsonFilePath;
import static com.linecorp.centraldogma.internal.Util.isValidYamlFilePath;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.internal.Jackson;

final class StructuredChange extends AbstractChange<JsonNode> {

    private final JsonNode jsonNode;
    @Nullable
    private final String structuredText;

    @Nullable
    private String contentAsText;

    StructuredChange(String path, ChangeType type, JsonNode jsonNode) {
        this(path, type, jsonNode, null);
    }

    StructuredChange(String path, ChangeType type, String structuredText) {
        this(path, type, null, structuredText);
    }

    private StructuredChange(String path, ChangeType type, @Nullable JsonNode jsonNode,
                             @Nullable String structuredText) {
        super(path, type);
        checkArgument(type.contentType() == JsonNode.class, "type.contentType() must be JsonNode.class");
        checkArgument(isValidJsonFilePath(path) || isValidYamlFilePath(path),
                      "Only JSON/YAML files are supported: %s", path);

        if (jsonNode != null) {
            this.jsonNode = jsonNode;
            this.structuredText = null;
        } else {
            assert structuredText != null;
            try {
                // Check if the structured text has a valid structure.
                this.jsonNode = Jackson.readTree(path, structuredText);
            } catch (JsonProcessingException e) {
                throw new ChangeFormatException("failed to read a value as a JSON tree. file: " + path, e);
            }
            this.structuredText = structuredText;
        }
    }

    @Override
    public JsonNode content() {
        return jsonNode;
    }

    @Nullable
    @Override
    public String rawContent() {
        return structuredText;
    }

    @Override
    public String contentAsText() {
        if (structuredText != null) {
            return structuredText;
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
        if (!(o instanceof StructuredChange)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final StructuredChange that = (StructuredChange) o;
        return Objects.equals(jsonNode, that.jsonNode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), jsonNode);
    }

    @Override
    public String toString() {
        String contentString;
        if (structuredText != null) {
            contentString = structuredText;
        } else {
            contentString = jsonNode.toString();
        }
        if (contentString.length() > 128) {
            contentString = contentString.substring(0, 128) + "...(length: " + contentString.length() + ')';
        }
        final ToStringHelper builder = MoreObjects.toStringHelper(this)
                                                  .add("type", type())
                                                  .add("path", path());
        if (structuredText != null) {
            builder.add("rawContent", contentString);
        } else {
            builder.add("content", contentString);
        }
        return builder.toString();
    }
}
