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
/*
 * Copyright (c) 2014, Francis Galiegue (fgaliegue@gmail.com)
 *
 * This software is dual-licensed under:
 *
 * - the Lesser General Public License (LGPL) version 3.0 or, at your option, any
 *   later version;
 * - the Apache Software License (ASL) version 2.0.
 *
 * The text of this file and of both licenses is available at the root of this
 * project or, if you have the jar distribution, in directory META-INF/, under
 * the names LGPL-3.0.txt and ASL-2.0.txt respectively.
 *
 * Direct link to the sources:
 *
 * - LGPL 3.0: https://www.gnu.org/licenses/lgpl-3.0.txt
 * - ASL 2.0: https://www.apache.org/licenses/LICENSE-2.0.txt
 */

package com.linecorp.centraldogma.common.jsonpatch;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON Patch {@code move} operation.
 *
 * <p>For this operation, {@code from} points to the value to move, and {@code
 * path} points to the new location of the moved value.</p>
 *
 * <p>It is an error condition if {@code from} does not point to a JSON value.
 * </p>
 *
 * <p>The specification adds another rule that the {@code from} path must not be
 * an immediate parent of {@code path}. Unfortunately, that doesn't really work.
 * Consider this patch:</p>
 *
 * <pre>
 *     { "op": "move", "from": "/0", "path": "/0/x" }
 * </pre>
 *
 * <p>Even though {@code /0} is an immediate parent of {@code /0/x}, when this
 * patch is applied to:</p>
 *
 * <pre>
 *     [ "victim", {} ]
 * </pre>
 *
 * <p>it actually succeeds and results in the patched value:</p>
 *
 * <pre>
 *     [ { "x": "victim" } ]
 * </pre>
 */
public final class MoveOperation extends DualPathOperation {

    /**
     * Creates a new instance.
     */
    @JsonCreator
    MoveOperation(@JsonProperty("from") final JsonPointer from,
                  @JsonProperty("path") final JsonPointer path) {
        super("move", from, path);
    }

    @Override
    public JsonNode apply(final JsonNode node) {
        requireNonNull(node, "node");
        final JsonPointer from = from();
        final JsonPointer path = path();
        if (from.equals(path)) {
            return node;
        }
        if (node.at(from).isMissingNode()) {
            throw new JsonPatchConflictException("non-existent source path: " + from);
        }

        final JsonNode sourceParent = ensureSourceParent(node, from);

        // Remove
        final String raw = from.last().getMatchingProperty();
        final JsonNode source;
        if (sourceParent.isObject()) {
            source = ((ObjectNode) sourceParent).remove(raw);
        } else {
            source = ((ArrayNode) sourceParent).remove(Integer.parseInt(raw));
        }

        // Add
        if (path.toString().isEmpty()) {
            return source;
        }

        final JsonNode targetParent = ensureTargetParent(node, path);
        return targetParent.isArray() ? AddOperation.addToArray(path, node, source)
                                      : AddOperation.addToObject(path, node, source);
    }
}
