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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializable;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;

import com.linecorp.centraldogma.internal.Jackson;

/**
 * Implementation of JSON Patch.
 *
 * <p><a href="https://tools.ietf.org/html/draft-ietf-appsawg-json-patch-10">JSON
 * Patch</a>, as its name implies, is an IETF draft describing a mechanism to
 * apply a patch to any JSON value. This implementation covers all operations
 * according to the specification; however, there are some subtle differences
 * with regards to some operations which are covered in these operations'
 * respective documentation.</p>
 *
 * <p>An example of a JSON Patch is as follows:</p>
 *
 * <pre>
 *     [
 *         {
 *             "op": "add",
 *             "path": "/-",
 *             "value": {
 *                 "productId": 19,
 *                 "name": "Duvel",
 *                 "type": "beer"
 *             }
 *         }
 *     ]
 * </pre>
 *
 * <p>This patch contains a single operation which adds an item at the end of
 * an array. A JSON Patch can contain more than one operation; in this case, all
 * operations are applied to the input JSON value in their order of appearance,
 * until all operations are applied or an error condition is encountered.</p>
 *
 * <p>The main point where this implementation differs from the specification
 * is initial JSON parsing. The draft says:</p>
 *
 * <pre>
 *     Operation objects MUST have exactly one "op" member
 * </pre>
 *
 * <p>and:</p>
 *
 * <pre>
 *     Additionally, operation objects MUST have exactly one "path" member.
 * </pre>
 *
 * <p>However, obeying these to the letter forces constraints on the JSON
 * <b>parser</b>. Here, these constraints are not enforced, which means:</p>
 *
 * <pre>
 *     [ { "op": "add", "op": "remove", "path": "/x" } ]
 * </pre>
 *
 * <p>is parsed (as a {@code remove} operation, since it appears last).</p>
 *
 * <p><b>IMPORTANT NOTE:</b> the JSON Patch is supposed to be VALID when the
 * constructor for this class ({@link JsonPatch#fromJson(JsonNode)} is used.</p>
 */
public final class JsonPatch implements JsonSerializable {

    private static final Equivalence<JsonNode> EQUIVALENCE = JsonNumEquals.getInstance();
    private static final JsonPointer EMPTY_JSON_POINTER = JsonPointer.compile("");
    private static final JsonPointer END_OF_ARRAY_POINTER = JsonPointer.compile("/-");

    /**
     * Static factory method to build a JSON Patch out of a JSON representation.
     *
     * @param node the JSON representation of the generated JSON Patch
     * @return a JSON Patch
     * @throws IOException input is not a valid JSON patch
     * @throws NullPointerException input is null
     */
    public static JsonPatch fromJson(final JsonNode node) throws IOException {
        requireNonNull(node, "node");
        try {
            return Jackson.treeToValue(node, JsonPatch.class);
        } catch (JsonMappingException e) {
            throw new JsonPatchException("invalid JSON patch", e);
        }
    }

    /**
     * Generates a JSON patch for transforming the source node into the target node.
     *
     * @param source the node to be patched
     * @param target the expected result after applying the patch
     * @param replaceMode the replace mode to be used
     * @return the patch as a {@link JsonPatch}
     */
    public static JsonPatch generate(final JsonNode source, final JsonNode target, ReplaceMode replaceMode) {
        requireNonNull(source, "source");
        requireNonNull(target, "target");
        final DiffProcessor processor = new DiffProcessor(replaceMode, () -> unchangedValues(source, target));
        generateDiffs(processor, EMPTY_JSON_POINTER, source, target);
        return processor.getPatch();
    }

    private static void generateDiffs(final DiffProcessor processor, final JsonPointer pointer,
                                      final JsonNode source, final JsonNode target) {

        if (EQUIVALENCE.equivalent(source, target)) {
            return;
        }

        final JsonNodeType sourceType = source.getNodeType();
        final JsonNodeType targetType = target.getNodeType();

        /*
         * Node types differ: generate a replacement operation.
         */
        if (sourceType != targetType) {
            processor.valueReplaced(pointer, source, target);
            return;
        }

        /*
         * If we reach this point, it means that both nodes are the same type,
         * but are not equivalent.
         *
         * If this is not a container, generate a replace operation.
         */
        if (!source.isContainerNode()) {
            processor.valueReplaced(pointer, source, target);
            return;
        }

        /*
         * If we reach this point, both nodes are either objects or arrays;
         * delegate.
         */
        if (sourceType == JsonNodeType.OBJECT) {
            generateObjectDiffs(processor, pointer, (ObjectNode) source, (ObjectNode) target);
        } else {
            // array
            generateArrayDiffs(processor, pointer, (ArrayNode) source, (ArrayNode) target);
        }
    }

    private static void generateObjectDiffs(final DiffProcessor processor, final JsonPointer pointer,
                                            final ObjectNode source, final ObjectNode target) {

        final Set<String> sourceFields = new TreeSet<>();
        Iterators.addAll(sourceFields, source.fieldNames());
        final Set<String> targetFields = new TreeSet<>();
        Iterators.addAll(targetFields, target.fieldNames());

        for (final String field : Sets.difference(sourceFields, targetFields)) {
            processor.valueRemoved(pointer.append(JsonPointer.valueOf('/' + field)));
        }

        for (final String field : Sets.difference(targetFields, sourceFields)) {
            processor.valueAdded(pointer.append(JsonPointer.valueOf('/' + field)), target.get(field));
        }

        for (final String field : Sets.intersection(sourceFields, targetFields)) {
            generateDiffs(processor, pointer.append(JsonPointer.valueOf('/' + field)),
                          source.get(field), target.get(field));
        }
    }

    private static void generateArrayDiffs(final DiffProcessor processor, final JsonPointer pointer,
                                           final ArrayNode source, final ArrayNode target) {
        final int sourceSize = source.size();
        final int targetSize = target.size();
        final int size = Math.min(sourceSize, targetSize);

        /*
         * Source array is larger; in this case, elements are removed from the
         * target; the index of removal is always the original arrays's length.
         */
        for (int index = size; index < sourceSize; index++) {
            processor.valueRemoved(pointer.append(JsonPointer.valueOf("/" + size)));
        }

        for (int index = 0; index < size; index++) {
            generateDiffs(processor, pointer.append(JsonPointer.valueOf("/" + index)),
                          source.get(index), target.get(index));
        }

        // Deal with the destination array being larger...
        for (int index = size; index < targetSize; index++) {
            processor.valueAdded(pointer.append(END_OF_ARRAY_POINTER), target.get(index));
        }
    }

    @VisibleForTesting
    static Map<JsonPointer, JsonNode> unchangedValues(final JsonNode source, final JsonNode target) {
        final Map<JsonPointer, JsonNode> ret = new HashMap<>();
        computeUnchanged(ret, EMPTY_JSON_POINTER, source, target);
        return ret;
    }

    private static void computeUnchanged(final Map<JsonPointer, JsonNode> ret, final JsonPointer pointer,
                                         final JsonNode source, final JsonNode target) {
        if (EQUIVALENCE.equivalent(source, target)) {
            ret.put(pointer, target);
            return;
        }

        final JsonNodeType sourceType = source.getNodeType();
        final JsonNodeType targetType = target.getNodeType();

        if (sourceType != targetType) {
            return; // nothing in common
        }

        // We know they are both the same type, so...

        switch (sourceType) {
            case OBJECT:
                computeUnchangedObject(ret, pointer, source, target);
                break;
            case ARRAY:
                computeUnchangedArray(ret, pointer, source, target);
                break;
            default:
                /* nothing */
        }
    }

    private static void computeUnchangedObject(final Map<JsonPointer, JsonNode> ret, final JsonPointer pointer,
                                               final JsonNode source, final JsonNode target) {
        final Iterator<String> sourceFields = source.fieldNames();
        while (sourceFields.hasNext()) {
            final String name = sourceFields.next();
            if (!target.has(name)) {
                continue;
            }
            computeUnchanged(ret, pointer.append(JsonPointer.valueOf('/' + name)),
                             source.get(name), target.get(name));
        }
    }

    private static void computeUnchangedArray(final Map<JsonPointer, JsonNode> ret, final JsonPointer pointer,
                                              final JsonNode source, final JsonNode target) {
        final int size = Math.min(source.size(), target.size());
        for (int i = 0; i < size; i++) {
            computeUnchanged(ret, pointer.append(JsonPointer.valueOf("/" + i)),
                             source.get(i), target.get(i));
        }
    }

    /**
     * List of operations.
     */
    private final List<JsonPatchOperation> operations;

    /**
     * Creates a new instance.
     *
     * @param operations the list of operations for this patch
     * @see JsonPatchOperation
     */
    @JsonCreator
    JsonPatch(final List<JsonPatchOperation> operations) {
        this.operations = ImmutableList.copyOf(operations);
    }

    /**
     * Returns whether this patch is empty.
     */
    public boolean isEmpty() {
        return operations.isEmpty();
    }

    /**
     * Returns the list of operations.
     */
    public List<JsonPatchOperation> operations() {
        return operations;
    }

    /**
     * Applies this patch to a JSON value.
     *
     * @param node the value to apply the patch to
     * @return the patched JSON value
     * @throws JsonPatchException failed to apply patch
     * @throws NullPointerException input is null
     */
    public JsonNode apply(final JsonNode node) {
        requireNonNull(node, "node");
        JsonNode ret = node.deepCopy();
        for (final JsonPatchOperation operation : operations) {
            ret = operation.apply(ret);
        }

        return ret;
    }

    /**
     * Converts this patch into JSON.
     */
    public ArrayNode toJson() {
        return (ArrayNode) Jackson.valueToTree(this);
    }

    @Override
    public String toString() {
        return operations.toString();
    }

    @Override
    public void serialize(final JsonGenerator jgen, final SerializerProvider provider) throws IOException {
        jgen.writeStartArray();
        for (final JsonPatchOperation op : operations) {
            op.serialize(jgen, provider);
        }
        jgen.writeEndArray();
    }

    @Override
    public void serializeWithType(final JsonGenerator jgen,
                                  final SerializerProvider provider, final TypeSerializer typeSer)
            throws IOException {
        serialize(jgen, provider);
    }
}
