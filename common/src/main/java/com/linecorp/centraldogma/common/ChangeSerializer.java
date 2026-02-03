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

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

final class ChangeSerializer extends StdSerializer<Change<?>> {

    private static final long serialVersionUID = 2753456329038813885L;

    ChangeSerializer() {
        super(Change.class, true);
    }

    @Override
    public void serialize(Change change, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", change.type().name());
        gen.writeStringField("path", change.path());

        if (change.type().contentType() == Void.class) {
            gen.writeEndObject();
            return;
        }

        final Object content = change.content();
        if (change.type().contentType() == String.class) {
            if (content != null) {
                // For text-based changes, always use 'content' field.
                gen.writeStringField("content", (String) content);
            }
            gen.writeEndObject();
            return;
        }

        assert change.type().contentType() == JsonNode.class;
        final String rawContent = change.rawContent();
        if (rawContent != null) {
            // Write 'rawContent' for non-text changes if available.
            gen.writeStringField("rawContent", rawContent);
        } else if (content != null) {
            gen.writeObjectField("content", content);
        }
        gen.writeEndObject();
    }
}
