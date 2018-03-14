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

package com.linecorp.centraldogma.internal.jsonpatch;

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

    @JsonCreator
    CopyOperation(@JsonProperty("from") final JsonPointer from,
                  @JsonProperty("path") final JsonPointer path) {
        super("copy", from, path);
    }

    @Override
    JsonNode apply(final JsonNode node) {
        JsonNode source = node.at(from);
        if (source.isMissingNode()) {
            throw new JsonPatchException("non-existent source path: " + from);
        }

        if (path.toString().isEmpty()) {
            return source;
        }

        final JsonNode targetParent = ensureTargetParent(node, path);
        source = source.deepCopy();
        return targetParent.isArray() ? AddOperation.addToArray(path, node, source)
                                      : AddOperation.addToObject(path, node, source);
    }
}
