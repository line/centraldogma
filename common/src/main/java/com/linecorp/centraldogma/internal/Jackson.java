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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.internal.Json5.isJson;
import static com.linecorp.centraldogma.internal.Json5.isJson5;
import static com.linecorp.centraldogma.internal.Json5.isJsonCompatible;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

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
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jsr310.deser.InstantDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.InstantSerializer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Configuration.Defaults;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.internal.Utils;
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
    private static final Configuration jsonPathKeysCfg = jsonPathCfg.addOptions(Option.AS_PATH_LIST);

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

    public static JsonNode readTree(String path, String data) throws JsonProcessingException {
        if (isJson(path)) {
            return readTree(data);
        } else if (isJson5(path)) {
            return Json5.readTree(data);
        } else if (Yaml.isYaml(path)) {
            return Yaml.readTree(data);
        } else {
            // Fallback to JSON parser.
            return readTree(data);
        }
    }

    public static JsonNode readTree(String path, byte[] data) throws JsonProcessingException {
        if (isJson(path)) {
            return readTree(data);
        } else if (isJson5(path)) {
            return Json5.readTree(data);
        } else if (Yaml.isYaml(path)) {
            return Yaml.readTree(data);
        } else {
            // Fallback to JSON parser.
            return readTree(data);
        }
    }

    public static JsonNode readTree(String data) throws JsonProcessingException {
        try {
            return compactMapper.readTree(data);
        } catch (JsonProcessingException e) {
            throw e;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public static JsonNode readTree(byte[] data) throws JsonProcessingException {
        try {
            return compactMapper.readTree(data);
        } catch (JsonProcessingException e) {
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

    public static String writeValueAsString(String path, Object value) throws JsonProcessingException {
        if (isJsonCompatible(path)) {
            return compactMapper.writeValueAsString(value);
        } else {
            return Yaml.writeValueAsString(value);
        }
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

    public static <T extends JsonNode> T valueToTree(Object value) {
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

        // A trailing '~' (e.g. '$.*~') extracts the keys of the matched nodes instead of their values.
        final boolean keysOnly = jsonPath.length() > 1 && jsonPath.endsWith("~");
        final String valuePath = keysOnly ? jsonPath.substring(0, jsonPath.length() - 1) : jsonPath;

        final JsonPath compiledJsonPath;
        try {
            compiledJsonPath = JsonPath.compile(valuePath);
        } catch (Exception e) {
            throw new QuerySyntaxException("invalid JSON path: " + jsonPath, e);
        }

        try {
            if (keysOnly) {
                return extractKeys(jsonNode, compiledJsonPath, multiPropertyNames(valuePath));
            }
            return JsonPath.parse(jsonNode, jsonPathCfg)
                           .read(compiledJsonPath, JsonNode.class);
        } catch (Exception e) {
            throw new QueryExecutionException("JSON path evaluation failed: " + jsonPath, e);
        }
    }

    private static JsonNode extractKeys(JsonNode jsonNode, JsonPath jsonPath,
                                        @Nullable List<String> selectedNames) {
        final ArrayNode keys = JsonNodeFactory.instance.arrayNode();
        final JsonNode paths;
        try {
            paths = JsonPath.parse(jsonNode, jsonPathKeysCfg).read(jsonPath, JsonNode.class);
        } catch (PathNotFoundException e) {
            if (jsonPath.isDefinite()) {
                throw e;
            }
            // An indefinite path without '~' also returns an empty array.
            return keys;
        }

        // A path like $['a','b'] matches the object which has the selected properties, so return
        // the selected names which exist in it, even if the path is definite.
        final String selector;
        final List<String> distinctNames;
        if (selectedNames != null) {
            selector = "['" + String.join("', '", selectedNames) + "']";
            distinctNames = selectedNames.stream().distinct().collect(toImmutableList());
        } else {
            selector = null;
            distinctNames = null;
        }

        for (JsonNode pathNode : paths) {
            final String path = pathNode.asText();
            if (selector == null) {
                final JsonNode key = walk(jsonNode, path, 1, true);
                checkArgument(key != null, "no key in path: %s", path);
                keys.add(key);
                continue;
            }

            final JsonNode parent = path.endsWith(selector) ?
                                    walk(jsonNode, path.substring(0, path.length() - selector.length()),
                                         1, false) : null;
            checkArgument(parent != null, "no key in path: %s", path);
            distinctNames.stream().filter(parent::has).forEach(keys::add);
        }
        return jsonPath.isDefinite() && selector == null ? keys.get(0) : keys;
    }

    /**
     * Follows the normalized path from the node and returns the key at the end of it if {@code returnKey}
     * is {@code true}, e.g. {@code "b"} for {@code $['a']['b']} and {@code 0} for {@code $['a'][0]}, or
     * the node at the end of it otherwise. json-path does not escape the names in the path, so this tries
     * each {@code ']} until the name before it exists in the node. If a name such as {@code a']['b} and
     * a nested path such as {@code a.b} both exist, they have the same path and the nested one is used.
     */
    @Nullable
    private static JsonNode walk(@Nullable JsonNode node, String path, int start, boolean returnKey) {
        if (node == null) {
            return null;
        }
        if (start == path.length()) {
            return returnKey ? null : node;
        }
        if (path.startsWith("['", start)) {
            for (int end = path.indexOf("']", start + 2); end >= 0; end = path.indexOf("']", end + 1)) {
                final String name = path.substring(start + 2, end);
                final JsonNode child = node.get(name);
                if (child == null) {
                    continue;
                }
                if (returnKey && end + 2 == path.length()) {
                    return TextNode.valueOf(name);
                }
                final JsonNode found = walk(child, path, end + 2, returnKey);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        final int end = path.indexOf(']', start);
        if (path.charAt(start) != '[' || end <= start + 1 ||
            !path.substring(start + 1, end).chars().allMatch(Character::isDigit)) {
            return null;
        }
        final int index = Integer.parseInt(path.substring(start + 1, end));
        if (returnKey && end + 1 == path.length()) {
            return node.has(index) ? IntNode.valueOf(index) : null;
        }
        return walk(node.get(index), path, end + 1, returnKey);
    }

    /**
     * Returns the property names in the bracket at the end of the JSON path if it has more than one,
     * e.g. {@code a} and {@code b} for {@code $['a','b']}, or {@code null} otherwise.
     */
    @Nullable
    private static List<String> multiPropertyNames(String jsonPath) {
        int i = skipSpaces(jsonPath, jsonPath.length() - 1);
        if (i < 0 || jsonPath.charAt(i) != ']') {
            return null;
        }
        final List<String> names = new ArrayList<>();
        while (true) {
            final int close = skipSpaces(jsonPath, i - 1);
            if (close < 0) {
                return null;
            }
            final char quote = jsonPath.charAt(close);
            if (quote != '\'' && quote != '"') {
                return null;
            }
            int open = close - 1;
            while (open >= 0 && (jsonPath.charAt(open) != quote || isEscaped(jsonPath, open))) {
                open--;
            }
            if (open < 0) {
                return null;
            }
            // Unescape in the same way as json-path, so that the names match the matched paths.
            names.add(0, Utils.unescape(jsonPath.substring(open + 1, close)));
            i = skipSpaces(jsonPath, open - 1);
            if (i < 0) {
                return null;
            }
            if (jsonPath.charAt(i) == '[') {
                return names.size() > 1 ? names : null;
            }
            if (jsonPath.charAt(i) != ',') {
                return null;
            }
        }
    }

    private static int skipSpaces(String str, int index) {
        while (index >= 0 && Character.isWhitespace(str.charAt(index))) {
            index--;
        }
        return index;
    }

    private static boolean isEscaped(String str, int index) {
        int backslashes = 0;
        while (index - backslashes > 0 && str.charAt(index - backslashes - 1) == '\\') {
            backslashes++;
        }
        return backslashes % 2 == 1;
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
