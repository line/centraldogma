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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * Deserializes JSON into a {@link Revision}.
 *
 * @see RevisionJsonSerializer
 */
public class RevisionJsonDeserializer extends StdDeserializer<Revision> {

    private static final long serialVersionUID = -2337105643062794190L;

    /**
     * Creates a new instance.
     */
    public RevisionJsonDeserializer() {
        super(Revision.class);
    }

    @Override
    public Revision deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        final JsonToken token = p.getCurrentToken();
        try {
            if (token.isNumeric()) {
                return new Revision(p.getIntValue());
            } else if (token == JsonToken.VALUE_STRING) {
                final String text = p.getText().trim();
                return new Revision(text);
            }
        } catch (IllegalArgumentException e) {
            ctx.reportInputMismatch(Revision.class, e.getMessage());
            // Should never reach here.
            throw new Error();
        }

        ctx.reportInputMismatch(Revision.class, "A revision must be a non-zero integer or a textual form.");
        // Should never reach here.
        throw new Error();
    }
}
