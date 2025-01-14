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
import java.util.Objects;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.google.common.base.Equivalence;

import com.linecorp.centraldogma.internal.jsonpatch.JsonNumEquals;

/**
 * Base class for patch operations taking a value in addition to a path.
 */
abstract class PathValueOperation extends JsonPatchOperation {

    private static final Equivalence<JsonNode> EQUIVALENCE = JsonNumEquals.getInstance();

    @JsonSerialize
    private final JsonNode value;

    /**
     * Creates a new instance.
     *
     * @param op operation name
     * @param path affected path
     * @param value JSON value
     */
    PathValueOperation(final String op, final JsonPointer path, final JsonNode value) {
        super(op, path);
        requireNonNull(value, "value");
        this.value = value.deepCopy();
    }

    /**
     * Returns the JSON value.
     */
    public JsonNode value() {
        return value;
    }

    JsonNode valueCopy() {
        return value.deepCopy();
    }

    void ensureEquivalence(JsonNode actual) {
        if (!EQUIVALENCE.equivalent(actual, value)) {
            throw new JsonPatchException("mismatching value at '" + path() + "': " +
                                         actual + " (expected: " + value + ')');
        }
    }

    @Override
    public final void serialize(final JsonGenerator jgen,
                                final SerializerProvider provider) throws IOException {
        requireNonNull(jgen, "jgen");
        jgen.writeStartObject();
        jgen.writeStringField("op", op());
        jgen.writeStringField("path", path().toString());
        jgen.writeFieldName("value");
        jgen.writeTree(value);
        jgen.writeEndObject();
    }

    @Override
    public final void serializeWithType(final JsonGenerator jgen,
                                        final SerializerProvider provider, final TypeSerializer typeSer)
            throws IOException {
        serialize(jgen, provider);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PathValueOperation)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final PathValueOperation that = (PathValueOperation) o;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), value);
    }

    @Override
    public final String toString() {
        return "op: " + op() + "; path: \"" + path() + "\"; value: " + value;
    }
}
