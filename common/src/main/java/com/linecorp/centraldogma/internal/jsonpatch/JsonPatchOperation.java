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

import static com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import static com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import static com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializable;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import com.linecorp.centraldogma.internal.Jackson;

@JsonTypeInfo(use = Id.NAME, include = As.PROPERTY, property = "op")

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

/**
 * Base abstract class for one patch operation.
 *
 * <p>Two more abstract classes extend this one according to the arguments of
 * the operation:</p>
 *
 * <ul>
 *     <li>{@link DualPathOperation} for operations taking a second pointer as
 *     an argument ({@code copy} and {@code move});</li>
 *     <li>{@link PathValueOperation} for operations taking a value as an
 *     argument ({@code add}, {@code replace} and {@code test}).</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class JsonPatchOperation implements JsonSerializable {

    /**
     * Converts {@link JsonPatchOperation}s to an array of {@link JsonNode}.
     */
    public static JsonNode asJsonArray(JsonPatchOperation... jsonPatchOperations) {
        requireNonNull(jsonPatchOperations, "jsonPatchOperations");
        return Jackson.valueToTree(jsonPatchOperations);
    }

    final String op;

    /*
     * Note: no need for a custom deserializer, Jackson will try and find a
     * constructor with a single string argument and use it.
     *
     * However, we need to serialize using .toString().
     */
    final JsonPointer path;

    /**
     * Creates a new instance.
     *
     * @param op the operation name
     * @param path the JSON Pointer for this operation
     */
    JsonPatchOperation(final String op, final JsonPointer path) {
        this.op = op;
        this.path = path;
    }

    public JsonPointer path() {
        return path;
    }

    /**
     * Applies this operation to a JSON value.
     *
     * @param node the value to patch
     * @return the patched value
     * @throws JsonPatchException operation failed to apply to this value
     */
    abstract JsonNode apply(JsonNode node);

    @Override
    public abstract String toString();

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
}
