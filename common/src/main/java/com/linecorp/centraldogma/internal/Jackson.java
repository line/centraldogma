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

package com.linecorp.centraldogma.internal;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.EnumSet;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.BufferRecyclers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.NullNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Configuration.Defaults;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;

import com.linecorp.centraldogma.common.QueryException;

public final class Jackson {

    private static final ObjectMapper compactMapper = new ObjectMapper();
    private static final ObjectMapper prettyMapper = new ObjectMapper();

    static {
        // Pretty-print the JSON when serialized via the mapper.
        compactMapper.disable(SerializationFeature.INDENT_OUTPUT);
        prettyMapper.enable(SerializationFeature.INDENT_OUTPUT);
        // Sort the attributes when serialized via the mapper.
        compactMapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        prettyMapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    private static final JsonFactory compactFactory = new JsonFactory(compactMapper);
    private static final JsonFactory prettyFactory = new JsonFactory(prettyMapper);

    static {
        // Configure the json-path library so it does not require other JSON libraries such as json-smart.
        final JsonProvider jsonProvider = new JacksonJsonNodeJsonProvider(prettyMapper);
        final MappingProvider mappingProvider = new JacksonMappingProvider(prettyMapper);
        final Set<Option> options = EnumSet.noneOf(Option.class);
        Configuration.setDefaults(new Defaults() {
            @Override
            public JsonProvider jsonProvider() {
                return jsonProvider;
            }

            @Override
            public Set<Option> options() {
                return options;
            }

            @Override
            public MappingProvider mappingProvider() {
                return mappingProvider;
            }
        });
    }

    public static final NullNode nullNode = NullNode.instance;

    public static void registerModules(Module... modules) {
        compactMapper.registerModules(modules);
        prettyMapper.registerModules(modules);
    }

    public static void registerSubtypes(NamedType...subtypes) {
        compactMapper.registerSubtypes(subtypes);
        prettyMapper.registerSubtypes(subtypes);
    }

    public static void registerSubtypes(Class<?>... subtypes) {
        compactMapper.registerSubtypes(subtypes);
        prettyMapper.registerSubtypes(subtypes);
    }

    public static <T> T readValue(String data, Class<T> type) throws IOException {
        return compactMapper.readValue(data, type);
    }

    public static <T> T readValue(byte[] data, Class<T> type) throws IOException {
        return compactMapper.readValue(data, type);
    }

    public static <T> T readValue(File file, Class<T> type) throws IOException {
        return compactMapper.readValue(file, type);
    }

    public static <T> T readValue(String data, TypeReference<T> typeReference) throws IOException {
        return compactMapper.readValue(data, typeReference);
    }

    public static <T> T readValue(byte[] data, TypeReference<T> typeReference) throws IOException {
        return compactMapper.readValue(data, typeReference);
    }

    public static <T> T readValue(File file, TypeReference<T> typeReference) throws IOException {
        return compactMapper.readValue(file, typeReference);
    }

    public static JsonNode readTree(String data) throws IOException {
        return compactMapper.readTree(data);
    }

    public static JsonNode readTree(byte[] data) throws IOException {
        return compactMapper.readTree(data);
    }

    public static byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
        return compactMapper.writeValueAsBytes(value);
    }

    public static String writeValueAsString(Object value) throws JsonProcessingException {
        return compactMapper.writeValueAsString(value);
    }

    public static String writeValueAsPrettyString(Object value) throws JsonProcessingException {
        return prettyMapper.writeValueAsString(value);
    }

    public static <T extends JsonNode> T valueToTree(Object value) {
        return compactMapper.valueToTree(value);
    }

    public static <T> T treeToValue(TreeNode node, Class<T> valueType) throws JsonProcessingException {
        return compactMapper.treeToValue(node, valueType);
    }

    public static <T> T convertValue(Object fromValue, Class<T> toValueType) {
        return compactMapper.convertValue(fromValue, toValueType);
    }

    public static <T> T convertValue(Object fromValue, TypeReference<?> toValueTypeRef) {
        return compactMapper.convertValue(fromValue, toValueTypeRef);
    }

    public static JsonGenerator createGenerator(Writer writer) throws IOException {
        return compactFactory.createGenerator(writer);
    }

    public static JsonGenerator createPrettyGenerator(Writer writer) throws IOException {
        JsonGenerator generator = prettyFactory.createGenerator(writer);
        generator.useDefaultPrettyPrinter();
        return generator;
    }

    public static String textValue(JsonNode node, String defaultValue) {
        return node != null && node.getNodeType() == JsonNodeType.STRING ? node.textValue() : defaultValue;
    }

    public static JsonNode extractTree(JsonNode jsonNode, String jsonPath) {
        requireNonNull(jsonNode, "jsonNode");
        requireNonNull(jsonPath, "jsonPath");

        final JsonPath compiledJsonPath;
        try {
            compiledJsonPath = JsonPath.compile(jsonPath);
        } catch (Exception e) {
            throw new QueryException("invalid JSON path: " + jsonPath, e);
        }

        try {
            return JsonPath.parse(jsonNode, Configuration.defaultConfiguration())
                           .read(compiledJsonPath, JsonNode.class);
        } catch (Exception e) {
            throw new QueryException("JSON path evaluation failed: " + jsonPath, e);
        }
    }

    public static String escapeText(String text) {
        final JsonStringEncoder enc = BufferRecyclers.getJsonStringEncoder();
        return new String(enc.quoteAsString(text));
    }

    private Jackson() {}
}
