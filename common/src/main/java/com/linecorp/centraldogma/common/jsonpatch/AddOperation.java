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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON Patch {@code add} operation.
 *
 * <p>For this operation, {@code path} is the JSON Pointer where the value
 * should be added, and {@code value} is the value to add.</p>
 *
 * <p>Note that if the target value pointed to by {@code path} already exists,
 * it is replaced. In this case, {@code add} is equivalent to {@code replace}.
 * </p>
 *
 * <p>Note also that a value will be created at the target path <b>if and only
 * if</b> the immediate parent of that value exists (and is of the correct
 * type).</p>
 *
 * <p>Finally, if the last reference token of the JSON Pointer is {@code -} and
 * the immediate parent is an array, the given value is added at the end of the
 * array. For instance, applying:</p>
 *
 * <pre>
 *     { "op": "add", "path": "/-", "value": 3 }
 * </pre>
 *
 * <p>to:</p>
 *
 * <pre>
 *     [ 1, 2 ]
 * </pre>
 *
 * <p>will give:</p>
 *
 * <pre>
 *     [ 1, 2, 3 ]
 * </pre>
 */
public final class AddOperation extends PathValueOperation {

    private static final String LAST_ARRAY_ELEMENT = "-";

    /**
     * Creates a new instance with the specified {@code path} and {@code value}.
     */
    @JsonCreator
    AddOperation(@JsonProperty("path") final JsonPointer path,
                 @JsonProperty("value") final JsonNode value) {
        super("add", path, value);
    }

    @Override
    public JsonNode apply(final JsonNode node) {
        requireNonNull(node, "node");
        final JsonPointer path = path();
        if (path.toString().isEmpty()) {
            return valueCopy();
        }

        final JsonNode targetParent = ensureTargetParent(node, path);
        return targetParent.isArray() ? addToArray(path, node, valueCopy())
                                      : addToObject(path, node, valueCopy());
    }

    static JsonNode addToArray(final JsonPointer path, final JsonNode node, final JsonNode value) {
        final ArrayNode target = (ArrayNode) node.at(path.head());
        final String rawToken = path.last().getMatchingProperty();

        if (rawToken.equals(LAST_ARRAY_ELEMENT)) {
            target.add(value);
            return node;
        }

        final int size = target.size();
        final int index;
        try {
            index = Integer.parseInt(rawToken);
        } catch (NumberFormatException ignored) {
            throw new JsonPatchConflictException("not an index: " + rawToken + " (expected: a non-negative integer)");
        }

        if (index < 0 || index > size) {
            throw new JsonPatchConflictException("index out of bounds: " + index +
                                                 " (expected: >= 0 && <= " + size + ')');
        }

        target.insert(index, value);
        return node;
    }

    static JsonNode addToObject(final JsonPointer path, final JsonNode node, final JsonNode value) {
        final ObjectNode target = (ObjectNode) node.at(path.head());
        target.set(path.last().getMatchingProperty(), value);
        return node;
    }
}
