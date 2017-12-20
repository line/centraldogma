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

package com.linecorp.centraldogma.internal.jsonpatch;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON Path {@code remove} operation.
 *
 * <p>This operation only takes one pointer ({@code path}) as an argument. It
 * is an error condition if no JSON value exists at that pointer.</p>
 */
public final class RemoveIfExistsOperation extends JsonPatchOperation {

    @JsonCreator
    public RemoveIfExistsOperation(@JsonProperty("path") final JsonPointer path) {
        super("removeIfExists", path);
    }

    @Override
    JsonNode apply(final JsonNode node) {
        if (path.toString().isEmpty()) {
            return MissingNode.getInstance();
        }

        final JsonNode found = node.at(path);
        if (found.isMissingNode()) {
            return node;
        }

        final JsonNode parentNode = node.at(path.head());
        final String raw = path.last().getMatchingProperty();
        if (parentNode.isObject()) {
            ((ObjectNode) parentNode).remove(raw);
        } else if (parentNode.isArray()) {
            ((ArrayNode) parentNode).remove(Integer.parseInt(raw));
        }
        return node;
    }

    @Override
    public void serialize(final JsonGenerator jgen,
                          final SerializerProvider provider) throws IOException {
        jgen.writeStartObject();
        jgen.writeStringField("op", "removeIfExists");
        jgen.writeStringField("path", path.toString());
        jgen.writeEndObject();
    }

    @Override
    public void serializeWithType(final JsonGenerator jgen,
                                  final SerializerProvider provider, final TypeSerializer typeSer)
            throws IOException {
        serialize(jgen, provider);
    }

    @Override
    public String toString() {
        return "op: " + op + "; path: \"" + path + '"';
    }
}
