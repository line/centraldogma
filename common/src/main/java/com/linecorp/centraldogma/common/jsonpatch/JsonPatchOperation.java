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

import static com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import static com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializable;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import com.linecorp.centraldogma.internal.Jackson;

/**
 * Base abstract class for one <a href="https://tools.ietf.org/html/draft-ietf-appsawg-json-patch-10">JSON
 * Patch</a> operation. A {@link JsonPatchOperation} can be converted into a JSON patch by calling
 * {@link #toJsonNode()}.
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
 */
@JsonTypeInfo(use = Id.NAME, property = "op")
@JsonSubTypes({
        @Type(name = "add", value = AddOperation.class),
        @Type(name = "copy", value = CopyOperation.class),
        @Type(name = "move", value = MoveOperation.class),
        @Type(name = "remove", value = RemoveOperation.class),
        @Type(name = "removeIfExists", value = RemoveIfExistsOperation.class),
        @Type(name = "replace", value = ReplaceOperation.class),
        @Type(name = "safeReplace", value = SafeReplaceOperation.class),
        @Type(name = "test", value = TestOperation.class),
        @Type(name = "testAbsence", value = TestAbsenceOperation.class)
})
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class JsonPatchOperation implements JsonSerializable {

    /**
     * Creates a new JSON Patch {@code add} operation.
     *
     * @param path the JSON Pointer for this operation
     * @param value the value to add
     */
    public static AddOperation add(JsonPointer path, JsonNode value) {
        return new AddOperation(path, value);
    }

    /**
     * Creates a new JSON Patch {@code copy} operation.
     *
     * @param from the source JSON Pointer
     * @param path the destination JSON Pointer
     */
    public static CopyOperation copy(JsonPointer from, JsonPointer path) {
        return new CopyOperation(from, path);
    }

    /**
     * Creates a new JSON Patch {@code move} operation.
     *
     * @param from the source JSON Pointer
     * @param path the destination JSON Pointer
     */
    public static MoveOperation move(JsonPointer from, JsonPointer path) {
        return new MoveOperation(from, path);
    }

    /**
     * Creates a new JSON Patch {@code remove} operation.
     *
     * <p>Note that this operation will throw an exception if the path does not exist.
     *
     * @param path the JSON Pointer to remove
     */
    public static RemoveOperation remove(JsonPointer path) {
        return new RemoveOperation(path);
    }

    /**
     * Creates a new JSON Patch {@code removeIfExists} operation.
     *
     * @param path the JSON Pointer to remove if it exists
     */
    public static RemoveIfExistsOperation removeIfExists(JsonPointer path) {
        return new RemoveIfExistsOperation(path);
    }

    /**
     * Creates a new JSON Patch {@code replace} operation.
     *
     * @param path the JSON Pointer for this operation
     * @param value the new value to replace the existing value
     */
    public static ReplaceOperation replace(JsonPointer path, JsonNode value) {
        return new ReplaceOperation(path, value);
    }

    /**
     * Creates a new JSON Patch {@code safeReplace} operation.
     *
     * @param path the JSON Pointer for this operation
     * @param oldValue the old value to replace
     * @param newValue the new value to replace the old value
     */
    public static SafeReplaceOperation safeReplace(JsonPointer path, JsonNode oldValue, JsonNode newValue) {
        return new SafeReplaceOperation(path, oldValue, newValue);
    }

    /**
     * Creates a new JSON Patch {@code test} operation.
     *
     * <p>This operation will throw an exception if the value at the path does not match the expected value.
     *
     * @param path the JSON Pointer for this operation
     * @param value the value to test
     */
    public static TestOperation test(JsonPointer path, JsonNode value) {
        return new TestOperation(path, value);
    }

    /**
     * Creates a new JSON Patch {@code testAbsent} operation.
     *
     * <p>This operation will throw an exception if the value at the path exists.
     *
     * @param path the JSON Pointer for this operation
     */
    public static TestAbsenceOperation testAbsence(JsonPointer path) {
        return new TestAbsenceOperation(path);
    }

    /**
     * Converts {@link JsonPatchOperation}s to an array of {@link JsonNode}.
     */
    public static JsonNode asJsonArray(JsonPatchOperation... jsonPatchOperations) {
        requireNonNull(jsonPatchOperations, "jsonPatchOperations");
        return Jackson.valueToTree(jsonPatchOperations);
    }

    /**
     * Converts {@link JsonPatchOperation}s to an array of {@link JsonNode}.
     */
    public static JsonNode asJsonArray(Iterable<? extends JsonPatchOperation> jsonPatchOperations) {
        requireNonNull(jsonPatchOperations, "jsonPatchOperations");
        return Jackson.valueToTree(jsonPatchOperations);
    }

    private final String op;

    /*
     * Note: no need for a custom deserializer, Jackson will try and find a
     * constructor with a single string argument and use it.
     *
     * However, we need to serialize using .toString().
     */
    private final JsonPointer path;

    /**
     * Creates a new instance.
     *
     * @param op the operation name
     * @param path the JSON Pointer for this operation
     */
    JsonPatchOperation(final String op, final JsonPointer path) {
        this.op = requireNonNull(op, "op");
        this.path = requireNonNull(path, "path");
    }

    /**
     * Returns the operation name.
     */
    public final String op() {
        return op;
    }

    /**
     * Returns the JSON Pointer for this operation.
     */
    public final JsonPointer path() {
        return path;
    }

    /**
     * Applies this operation to a JSON value.
     *
     * @param node the value to patch
     * @return the patched value
     * @throws JsonPatchException operation failed to apply to this value
     */
    public abstract JsonNode apply(JsonNode node);

    /**
     * Converts this {@link JsonPatchOperation} to a {@link JsonNode}.
     */
    public JsonNode toJsonNode() {
        return JsonNodeFactory.instance.arrayNode().add(Jackson.valueToTree(this));
    }

    JsonNode ensureExistence(JsonNode node) {
        final JsonNode found = node.at(path);
        if (found.isMissingNode()) {
            throw new JsonPatchException("non-existent path: " + path);
        }
        return found;
    }

    static JsonNode ensureSourceParent(JsonNode node, JsonPointer path) {
        return ensureParent(node, path, "source");
    }

    static JsonNode ensureTargetParent(JsonNode node, JsonPointer path) {
        return ensureParent(node, path, "target");
    }

    private static JsonNode ensureParent(JsonNode node, JsonPointer path, String typeName) {
        /*
         * Check the parent node: it must exist and be a container (ie an array
         * or an object) for the add operation to work.
         */
        final JsonPointer parentPath = path.head();
        final JsonNode parentNode = node.at(parentPath);
        if (parentNode.isMissingNode()) {
            throw new JsonPatchException("non-existent " + typeName + " parent: " + parentPath);
        }
        if (!parentNode.isContainerNode()) {
            throw new JsonPatchException(typeName + " parent is not a container: " + parentPath +
                                         " (" + parentNode.getNodeType() + ')');
        }
        return parentNode;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JsonPatchOperation)) {
            return false;
        }
        final JsonPatchOperation that = (JsonPatchOperation) o;
        return op.equals(that.op) && path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(op, path);
    }

    @Override
    public abstract String toString();
}
