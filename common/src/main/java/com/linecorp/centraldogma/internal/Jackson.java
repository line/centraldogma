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

import static com.fasterxml.jackson.databind.node.JsonNodeType.OBJECT;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.Iterator;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.core.io.SegmentedStringWriter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.deser.InstantDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.InstantSerializer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Configuration.Defaults;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;

import com.linecorp.centraldogma.common.QueryExecutionException;
import com.linecorp.centraldogma.common.QuerySyntaxException;

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

        registerModules(new SimpleModule().addSerializer(Instant.class, InstantSerializer.INSTANCE)
                                          .addDeserializer(Instant.class, InstantDeserializer.INSTANT));
    }

    private static final JsonFactory compactFactory = new JsonFactory(compactMapper);
    private static final JsonFactory prettyFactory = new JsonFactory(prettyMapper);
    private static final Configuration jsonPathCfg =
            Configuration.builder()
                         .jsonProvider(new JacksonJsonNodeJsonProvider())
                         .mappingProvider(new JacksonMappingProvider(prettyMapper))
                         .build();

    static {
        // If the json-path library is shaded, its transitive dependency 'json-smart' should not be required.
        // Override the default configuration so that json-path does not attempt to load the json-smart classes.
        if (Configuration.class.getPackage().getName().endsWith(".shaded.jsonpath")) {
            Configuration.setDefaults(new Defaults() {
                @Override
                public JsonProvider jsonProvider() {
                    return jsonPathCfg.jsonProvider();
                }

                @Override
                public Set<Option> options() {
                    return jsonPathCfg.getOptions();
                }

                @Override
                public MappingProvider mappingProvider() {
                    return jsonPathCfg.mappingProvider();
                }
            });
        }
    }

    public static final NullNode nullNode = NullNode.instance;

    public static void registerModules(Module... modules) {
        compactMapper.registerModules(modules);
        prettyMapper.registerModules(modules);
    }

    public static void registerSubtypes(NamedType... subtypes) {
        compactMapper.registerSubtypes(subtypes);
        prettyMapper.registerSubtypes(subtypes);
    }

    public static void registerSubtypes(Class<?>... subtypes) {
        compactMapper.registerSubtypes(subtypes);
        prettyMapper.registerSubtypes(subtypes);
    }

    public static <T> T readValue(String data, Class<T> type) throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(data, type);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public static <T> T readValue(byte[] data, Class<T> type) throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(data, type);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public static <T> T readValue(File file, Class<T> type) throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(file, type);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public static <T> T readValue(String data, TypeReference<T> typeReference)
            throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(data, typeReference);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public static <T> T readValue(byte[] data, TypeReference<T> typeReference)
            throws JsonParseException, JsonMappingException {
        try {
            return compactMapper.readValue(data, typeReference);
        } catch (JsonParseException | JsonMappingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public static <T> T readValue(File file, TypeReference<T> typeReference) throws IOException {
        return compactMapper.readValue(file, typeReference);
    }

    public static <T> T readValue(JsonParser jp, TypeReference<T> typeReference) throws IOException {
        return compactMapper.readValue(jp, typeReference);
    }

    public static JsonNode readTree(String data) throws JsonParseException {
        try {
            return compactMapper.readTree(data);
        } catch (JsonParseException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public static JsonNode readTree(byte[] data) throws JsonParseException {
        try {
            return compactMapper.readTree(data);
        } catch (JsonParseException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public static byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
        return compactMapper.writeValueAsBytes(value);
    }

    public static String writeValueAsString(Object value) throws JsonProcessingException {
        return compactMapper.writeValueAsString(value);
    }

    public static String writeValueAsPrettyString(Object value) throws JsonProcessingException {
        // XXX(trustin): prettyMapper.writeValueAsString() does not respect the custom pretty printer
        //               set via ObjectMapper.setDefaultPrettyPrinter() for an unknown reason, so we
        //               create a generator manually and set the pretty printer explicitly.
        final JsonFactory factory = prettyMapper.getFactory();
        final SegmentedStringWriter sw = new SegmentedStringWriter(factory._getBufferRecycler());
        try {
            final JsonGenerator g = prettyMapper.getFactory().createGenerator(sw);
            g.setPrettyPrinter(new PrettyPrinterImpl());
            prettyMapper.writeValue(g, value);
            return sw.getAndClear();
        } catch (JsonProcessingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public static <T extends JsonNode> T valueToTree(@Nullable Object value) {
        return compactMapper.valueToTree(value);
    }

    public static <T> T treeToValue(TreeNode node, Class<T> valueType)
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

    public static <T> T convertValue(Object fromValue, Class<T> toValueType) {
        return compactMapper.convertValue(fromValue, toValueType);
    }

    public static <T> T convertValue(Object fromValue, TypeReference<T> toValueTypeRef) {
        return compactMapper.convertValue(fromValue, toValueTypeRef);
    }

    public static JsonGenerator createGenerator(Writer writer) throws IOException {
        return compactFactory.createGenerator(writer);
    }

    public static JsonGenerator createPrettyGenerator(Writer writer) throws IOException {
        final JsonGenerator generator = prettyFactory.createGenerator(writer);
        generator.useDefaultPrettyPrinter();
        return generator;
    }

    public static String textValue(JsonNode node, String defaultValue) {
        return node != null && node.getNodeType() == JsonNodeType.STRING ? node.textValue() : defaultValue;
    }

    public static JsonNode extractTree(JsonNode jsonNode, Iterable<String> jsonPaths) {
        for (String jsonPath : jsonPaths) {
            jsonNode = extractTree(jsonNode, jsonPath);
        }
        return jsonNode;
    }

    public static JsonNode extractTree(JsonNode jsonNode, String jsonPath) {
        requireNonNull(jsonNode, "jsonNode");
        requireNonNull(jsonPath, "jsonPath");

        final JsonPath compiledJsonPath;
        try {
            compiledJsonPath = JsonPath.compile(jsonPath);
        } catch (Exception e) {
            throw new QuerySyntaxException("invalid JSON path: " + jsonPath, e);
        }

        try {
            return JsonPath.parse(jsonNode, jsonPathCfg)
                           .read(compiledJsonPath, JsonNode.class);
        } catch (Exception e) {
            throw new QueryExecutionException("JSON path evaluation failed: " + jsonPath, e);
        }
    }

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

    private Jackson() {}

    private static class PrettyPrinterImpl extends DefaultPrettyPrinter {
        private static final long serialVersionUID = 8408886209309852098L;

        // The default object indenter uses platform-dependent line separator, so we define one
        // with a fixed separator (\n).
        private static final Indenter objectIndenter = new DefaultIndenter("  ", "\n");

        @SuppressWarnings("AssignmentToSuperclassField")
        PrettyPrinterImpl() {
            _objectFieldValueSeparatorWithSpaces = ": ";
            _objectIndenter = objectIndenter;
        }
    }
}
