/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.centraldogma.internal.jackson;

import static com.fasterxml.jackson.databind.node.JsonNodeType.OBJECT;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.Iterator;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.deser.InstantDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.InstantSerializer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.QueryExecutionException;

public abstract class Jackson {
    private final ObjectMapper compactMapper;
    private final JsonFactory compactFactory;
    private final JsonFactory prettyFactory;

    public static final NullNode nullNode = NullNode.instance;

    protected Jackson(ObjectMapper compactMapper, JsonFactory compactFactory, JsonFactory prettyFactory) {
        this.compactMapper = compactMapper;
        this.compactFactory = compactFactory;
        this.prettyFactory = prettyFactory;

        registerModules1(new SimpleModule().addSerializer(Instant.class, InstantSerializer.INSTANCE)
                                           .addDeserializer(Instant.class, InstantDeserializer.INSTANT));
    }

    public static Jackson ofJson() {
        return JacksonJson.INSTANCE;
    }

    public static Jackson ofYaml() {
        return JacksonYaml.INSTANCE;
    }

    public static Jackson of(EntryType type) {
        if (type == EntryType.JSON) {
            return JacksonJson.INSTANCE;
        }
        if (type == EntryType.YAML) {
            return JacksonYaml.INSTANCE;
        }
        // Never reach here
        throw new Error();
    }

    public static void registerModules(Module... modules) {
        ofJson().registerModules1(modules);
        ofYaml().registerModules1(modules);
    }

    public static void registerSubtypes(NamedType... subtypes) {
        ofJson().registerSubtypes1(subtypes);
        ofYaml().registerSubtypes1(subtypes);
    }

    public static void registerSubtypes(Class<?>... subtypes) {
        ofJson().registerSubtypes1(subtypes);
        ofYaml().registerSubtypes1(subtypes);
    }

    protected abstract void registerModules1(Module... modules);

    protected abstract void registerSubtypes1(NamedType... subtypes);

    protected abstract void registerSubtypes1(Class<?>... subtypes);

    public <T> T readValue(String data, Class<T> type) throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(data, type);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public <T> T readValue(byte[] data, Class<T> type) throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(data, type);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public <T> T readValue(File file, Class<T> type) throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(file, type);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public <T> T readValue(String data, TypeReference<T> typeReference)
            throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(data, typeReference);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public <T> T readValue(byte[] data, TypeReference<T> typeReference)
            throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(data, typeReference);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public <T> T readValue(File file, TypeReference<T> typeReference) throws IOException {
        return compactMapper.readValue(file, typeReference);
    }

    public JsonNode readTree(@Nullable String data) throws JsonParseException {
        if (data == null) {
            return nullNode;
        }
        try {
            return compactMapper.readTree(data);
        } catch (JsonParseException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public JsonNode readTree(@Nullable byte[] data) throws JsonParseException {
        if (data == null) {
            return nullNode;
        }
        try {
            return compactMapper.readTree(data);
        } catch (JsonParseException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
        return compactMapper.writeValueAsBytes(value);
    }

    public String writeValueAsString(Object value) throws JsonProcessingException {
        return compactMapper.writeValueAsString(value);
    }

    public abstract String writeValueAsPrettyString(Object value) throws JsonProcessingException;

    public <T extends JsonNode> T valueToTree(Object value) {
        return compactMapper.valueToTree(value);
    }

    public <T> T treeToValue(TreeNode node, Class<T> valueType)
            throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.treeToValue(node, valueType);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (JsonProcessingException e) {
            // Should never reach here.
            throw new IllegalStateException(e);
        }
    }

    public <T> T convertValue(Object fromValue, Class<T> toValueType) {
        return compactMapper.convertValue(fromValue, toValueType);
    }

    public <T> T convertValue(Object fromValue, TypeReference<T> toValueTypeRef) {
        return compactMapper.convertValue(fromValue, toValueTypeRef);
    }

    public JsonGenerator createGenerator(Writer writer) throws IOException {
        return compactFactory.createGenerator(writer);
    }

    public JsonGenerator createPrettyGenerator(Writer writer) throws IOException {
        final JsonGenerator generator = prettyFactory.createGenerator(writer);
        generator.useDefaultPrettyPrinter();
        return generator;
    }

    public String textValue(@Nullable JsonNode node, String defaultValue) {
        return node != null && node.getNodeType() == JsonNodeType.STRING ? node.textValue() : defaultValue;
    }

    public JsonNode extractTree(JsonNode jsonNode, Iterable<String> jsonPaths) {
        for (String jsonPath : jsonPaths) {
            jsonNode = extractTree(jsonNode, jsonPath);
        }
        return jsonNode;
    }

    public abstract JsonNode extractTree(JsonNode jsonNode, String jsonPath);

    public static String escapeText(String text) {
        final JsonStringEncoder enc = JsonStringEncoder.getInstance();
        return new String(enc.quoteAsString(text));
    }

    public static JsonNode mergeTree(JsonNode... jsonNodes) {
        return mergeTree(ImmutableList.copyOf(requireNonNull(jsonNodes, "jsonNodes")));
    }

    public static JsonNode mergeTree(Iterable<JsonNode> jsonNodes) {
        requireNonNull(jsonNodes, "jsonNodes");
        final int size = Iterables.size(jsonNodes);
        checkArgument(size > 0, "jsonNodes is empty.");
        final Iterator<JsonNode> it = jsonNodes.iterator();
        final JsonNode first = it.next();
        JsonNode merged = first.deepCopy();

        final StringBuilder fieldNameAppender = new StringBuilder("/");
        while (it.hasNext()) {
            final JsonNode addition = it.next();
            merged = traverse(merged, addition, fieldNameAppender, true, true);
        }

        if (size > 2) {
            // Traverse once more to find the mismatched value between the first and the merged node.
            traverse(first, merged, fieldNameAppender, false, true);
        }
        return merged;
    }

    private static JsonNode traverse(JsonNode base, JsonNode update, StringBuilder fieldNameAppender,
                                     boolean isMerging, boolean isRoot) {
        if (base.isObject() && update.isObject()) {
            final ObjectNode baseObject = (ObjectNode) base;
            final Iterator<String> fieldNames = update.fieldNames();
            while (fieldNames.hasNext()) {
                final String fieldName = fieldNames.next();
                final JsonNode baseValue = baseObject.get(fieldName);
                final JsonNode updateValue = update.get(fieldName);

                if (baseValue == null || baseValue.isNull() || updateValue.isNull()) {
                    if (isMerging) {
                        baseObject.set(fieldName, updateValue);
                    }
                    continue;
                }

                final int length = fieldNameAppender.length();
                // Append the filed name and traverse the child.
                fieldNameAppender.append(fieldName);
                fieldNameAppender.append('/');
                final JsonNode traversed =
                        traverse(baseValue, updateValue, fieldNameAppender, isMerging, false);
                if (isMerging) {
                    baseObject.set(fieldName, traversed);
                }
                // Remove the appended filed name above.
                fieldNameAppender.delete(length, fieldNameAppender.length());
            }

            return base;
        }

        if (isRoot || (base.getNodeType() != update.getNodeType() && (!base.isNull() || !update.isNull()))) {
            throw new QueryExecutionException("Failed to merge tree. " + fieldNameAppender +
                                              " type: " + update.getNodeType() +
                                              " (expected: " + (isRoot ? OBJECT : base.getNodeType()) + ')');
        }

        return update;
    }
}
