/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.centraldogma.common.jsonpatch;

import static java.util.Objects.requireNonNull;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;

/**
 * JSON Patch {@code testAbsence} operation.
 *
 * <p>For this operation, {@code path} points to the value to test absence.</p>
 *
 * <p>It is an error condition if {@code path} points to an actual JSON value.</p>
 */
public final class TestAbsenceOperation extends JsonPatchOperation {

    /**
     * Creates a new instance.
     */
    @JsonCreator
    TestAbsenceOperation(@JsonProperty("path") final JsonPointer path) {
        super("testAbsence", path);
    }

    @Override
    public JsonNode apply(JsonNode node) {
        requireNonNull(node, "node");
        final JsonPointer path = path();
        final JsonNode found = node.at(path);
        if (!found.isMissingNode()) {
            throw new JsonPatchException("existent path: " + path);
        }
        return node;
    }

    @Override
    public void serialize(JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("op", op());
        gen.writeStringField("path", path().toString());
        gen.writeEndObject();
    }

    @Override
    public void serializeWithType(JsonGenerator gen, SerializerProvider serializers, TypeSerializer typeSer)
            throws IOException {
        serialize(gen, serializers);
    }

    @Override
    public String toString() {
        return "op: " + op() + "; path: \"" + path() + '"';
    }
}
