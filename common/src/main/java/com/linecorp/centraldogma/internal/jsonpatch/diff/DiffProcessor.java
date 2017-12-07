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

package com.linecorp.centraldogma.internal.jsonpatch.diff;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Equivalence;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatchOperation;
import com.linecorp.centraldogma.internal.jsonpatch.ReplaceMode;
import com.linecorp.centraldogma.internal.jsonpatch.utils.JsonNumEquals;

// TODO: cleanup
final class DiffProcessor
{
    private static final Equivalence<JsonNode> EQUIVALENCE
        = JsonNumEquals.getInstance();

    private final Map<JsonPointer, JsonNode> unchanged;

    private final List<DiffOperation> diffs = Lists.newArrayList();

    private final ReplaceMode replaceMode;

    DiffProcessor(final Map<JsonPointer, JsonNode> unchanged, ReplaceMode replaceMode)
    {
        this.unchanged = ImmutableMap.copyOf(unchanged);
        this.replaceMode = replaceMode;
    }

    void valueReplaced(final JsonPointer pointer, final JsonNode oldValue,
        final JsonNode newValue)
    {
        switch (replaceMode) {
        case RFC6902:
            diffs.add(DiffOperation.replace(pointer, oldValue, newValue));
            break;
        case SAFE:
            diffs.add(DiffOperation.safeReplace(pointer, oldValue, newValue));
            break;
        }
    }

    void valueRemoved(final JsonPointer pointer, final JsonNode value)
    {
        diffs.add(DiffOperation.remove(pointer, value));
    }

    void valueAdded(final JsonPointer pointer, final JsonNode value)
    {
        final DiffOperation op;
        if (value.isContainerNode()) {
            // Use copy operation only for container nodes.
            final JsonPointer ptr = findUnchangedValue(value);
            op = ptr != null ? DiffOperation.copy(ptr, pointer, value)
                             : DiffOperation.add(pointer, value);
        } else {
            op = DiffOperation.add(pointer, value);
        }

        diffs.add(op);
    }

    JsonPatch getPatch()
    {
        final List<JsonPatchOperation> list = Lists.newArrayList();

        for (final DiffOperation op: diffs)
            list.add(op.asJsonPatchOperation());

        return new JsonPatch(list);
    }

    @Nullable
    private JsonPointer findUnchangedValue(final JsonNode value)
    {
        final Predicate<JsonNode> predicate = EQUIVALENCE.equivalentTo(value);
        for (final Map.Entry<JsonPointer, JsonNode> entry: unchanged.entrySet())
            if (predicate.apply(entry.getValue()))
                return entry.getKey();
        return null;
    }
}
