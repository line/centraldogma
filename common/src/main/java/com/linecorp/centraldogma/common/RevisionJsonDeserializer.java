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
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * Deserializes JSON into a {@link Revision}.
 *
 * @see RevisionJsonSerializer
 */
public class RevisionJsonDeserializer extends StdDeserializer<Revision> {

    private static final long serialVersionUID = -2337105643062794190L;

    public RevisionJsonDeserializer() {
        super(Revision.class);
    }

    @Override
    public Revision deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        final JsonNode node = p.readValueAsTree();
        if (node.isNumber()) {
            validateRevisionNumber(ctx, node, "major", false);
            return new Revision(node.intValue());
        }

        if (node.isTextual()) {
            try {
                return new Revision(node.textValue());
            } catch (IllegalArgumentException e) {
                ctx.reportInputMismatch(Revision.class, e.getMessage());
                // Should never reach here.
                throw new Error();
            }
        }

        if (!node.isObject()) {
            ctx.reportInputMismatch(Revision.class,
                                    "A revision must be a non-zero integer or " +
                                    "an object that contains \"major\" and \"minor\" properties.");
            // Should never reach here.
            throw new Error();
        }

        final JsonNode majorNode = node.get("major");
        final JsonNode minorNode = node.get("minor");
        final int major;

        validateRevisionNumber(ctx, majorNode, "major", false);
        major = majorNode.intValue();
        if (minorNode != null) {
            validateRevisionNumber(ctx, minorNode, "minor", true);
            if (minorNode.intValue() != 0) {
                ctx.reportInputMismatch(Revision.class,
                                        "A revision must not have a non-zero \"minor\" property.");
            }
        }

        return new Revision(major);
    }

    private static void validateRevisionNumber(DeserializationContext ctx, JsonNode node,
                                               String type, boolean zeroAllowed) throws JsonMappingException {
        if (node == null) {
            ctx.reportInputMismatch(Revision.class, "missing %s revision number", type);
            // Should never reach here.
            throw new Error();
        }

        if (!node.canConvertToInt() || !zeroAllowed && node.intValue() == 0) {
            ctx.reportInputMismatch(Revision.class,
                                    "A %s revision number must be %s integer.",
                                    type, zeroAllowed ? "an" : "a non-zero");
            // Should never reach here.
            throw new Error();
        }
    }
}
