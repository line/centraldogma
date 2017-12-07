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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Equivalence;
import com.google.common.base.Predicate;

// TODO: cleanup
final class DiffProcessor {

    private static final Equivalence<JsonNode> EQUIVALENCE = JsonNumEquals.getInstance();

    private final List<JsonPatchOperation> diffs = new ArrayList<>();
    private final ReplaceMode replaceMode;
    private final Supplier<Map<JsonPointer, JsonNode>> unchangedValuesSupplier;

    DiffProcessor(ReplaceMode replaceMode, final Supplier<Map<JsonPointer, JsonNode>> unchangedValuesSupplier) {
        this.replaceMode = replaceMode;
        this.unchangedValuesSupplier = new Supplier<Map<JsonPointer, JsonNode>>() {

            private Map<JsonPointer, JsonNode> unchangedValues;

            @Override
            public Map<JsonPointer, JsonNode> get() {
                if (unchangedValues == null) {
                    unchangedValues = unchangedValuesSupplier.get();
                }
                return unchangedValues;
            }
        };
    }

    void valueReplaced(final JsonPointer pointer, final JsonNode oldValue, final JsonNode newValue) {
        switch (replaceMode) {
            case RFC6902:
                diffs.add(new ReplaceOperation(pointer, newValue));
                break;
            case SAFE:
                diffs.add(new SafeReplaceOperation(pointer, oldValue, newValue));
                break;
        }
    }

    void valueRemoved(final JsonPointer pointer) {
        diffs.add(new RemoveOperation(pointer));
    }

    void valueAdded(final JsonPointer pointer, final JsonNode value) {
        final JsonPatchOperation op;
        if (value.isContainerNode()) {
            // Use copy operation only for container nodes.
            final JsonPointer ptr = findUnchangedValue(value);
            op = ptr != null ? new CopyOperation(ptr, pointer)
                             : new AddOperation(pointer, value);
        } else {
            op = new AddOperation(pointer, value);
        }

        diffs.add(op);
    }

    JsonPatch getPatch() {
        return new JsonPatch(diffs);
    }

    @Nullable
    private JsonPointer findUnchangedValue(final JsonNode value) {
        final Map<JsonPointer, JsonNode> unchangedValues = unchangedValuesSupplier.get();
        if (unchangedValues.isEmpty()) {
            return null;
        }

        final Predicate<JsonNode> predicate = EQUIVALENCE.equivalentTo(value);
        for (final Map.Entry<JsonPointer, JsonNode> entry : unchangedValues.entrySet()) {
            if (predicate.apply(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }
}
