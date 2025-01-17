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

/**
 * JSON Patch {@code copy} operation.
 *
 * <p>For this operation, {@code from} is the JSON Pointer of the value to copy,
 * and {@code path} is the destination where the value should be copied.</p>
 *
 * <p>It is an error if {@code from} fails to resolve to a JSON value.</p>
 */
public final class CopyOperation extends DualPathOperation {

    /**
     * Creates a new instance.
     */
    @JsonCreator
    CopyOperation(@JsonProperty("from") final JsonPointer from,
                  @JsonProperty("path") final JsonPointer path) {
        super("copy", from, path);
    }

    @Override
    public JsonNode apply(final JsonNode node) {
        requireNonNull(node, "node");
        final JsonPointer from = from();
        JsonNode source = node.at(from);
        if (source.isMissingNode()) {
            throw new JsonPatchConflictException("non-existent source path: " + from);
        }

        final JsonPointer path = path();
        if (path.toString().isEmpty()) {
            return source;
        }

        final JsonNode targetParent = ensureTargetParent(node, path);
        source = source.deepCopy();
        return targetParent.isArray() ? AddOperation.addToArray(path, node, source)
                                      : AddOperation.addToObject(path, node, source);
    }
}
