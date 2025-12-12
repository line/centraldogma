/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;

class Json5Test {

    // Supported JSON5 Features

    @Test
    void singleLineComments() throws Exception {
        //language=JSON5
        final String json5 = "{\n" +
                             "  // This is a single-line comment\n" +
                             "  \"key\": \"value\"\n" +
                             '}';
        final JsonNode node = Json5.readTree(json5);
        assertThatJson(node).node("key").isEqualTo("value");
    }

    @Test
    void multiLineComments() throws Exception {
        //language=JSON5
        final String json5 = "{\n" +
                             "  /* This is a\n" +
                             "     multi-line comment */\n" +
                             "  \"key\": \"value\"\n" +
                             '}';
        final JsonNode node = Json5.readTree(json5);
        assertThatJson(node).node("key").isEqualTo("value");
    }

    @Test
    void singleQuotes() throws Exception {
        //language=JSON5
        final String json5 = "{\n" +
                             "  'key': 'value',\n" +
                             "  'string': 'I can use \"double quotes\" here'\n" +
                             '}';
        final JsonNode node = Json5.readTree(json5);
        assertThatJson(node).node("key").isEqualTo("value");
        assertThatJson(node).node("string").isEqualTo("I can use \\\"double quotes\\\" here");
    }

    @Test
    void unquotedKeys() throws Exception {
        //language=JSON5
        final String json5 = "{\n" +
                             "  unquoted: 'and you can quote me on that',\n" +
                             "  $valid: true,\n" +
                             "  _alsoValid: 123\n" +
                             '}';
        final JsonNode node = Json5.readTree(json5);
        assertThatJson(node).node("unquoted").isEqualTo("and you can quote me on that");
        assertThatJson(node).node("$valid").isEqualTo(true);
        assertThatJson(node).node("_alsoValid").isEqualTo(123);
    }

    @Test
    void multiLineStringLiterals() throws JsonParseException {
        // Multi-line string literals using backslash
        //language=JSON5
        final String json5 = "{ 'text': 'Hello \\\n" +
                             "World' }";
        final JsonNode node = Json5.readTree(json5);
        assertThatJson(node).node("text").isEqualTo("Hello \\nWorld");
    }

    @Test
    void multiLineStringWithIndentation() throws JsonParseException {
        // Multi-line string with indentation
        //language=JSON5
        final String json5 = "{\n" +
                             "  'message': 'This is a \\\n" +
                             "              long message'\n" +
                             '}';
        final JsonNode node = Json5.readTree(json5);
        assertThatJson(node).node("message")
                            .isEqualTo("This is a \\n              long message");
    }

    @Test
    void multiLineStringInArray() throws JsonParseException {
        // Multi-line string in array
        //language=JSON5
        final String json5 = "{\n" +
                             "  'messages': [\n" +
                             "    'First line \\\n" +
                             "     Second line'\n" +
                             "  ]\n" +
                             '}';
        final JsonNode node = Json5.readTree(json5);
        assertThatJson(node).node("messages[0]")
                            .isEqualTo("First line \\n     Second line");
    }

    @Test
    void multiLineStringNested() throws JsonParseException {
        // Multi-line string in nested object
        //language=JSON5
        final String json5 = "{\n" +
                             "  'config': {\n" +
                             "    'description': 'Line 1 \\\n" +
                             "                    Line 2'\n" +
                             "  }\n" +
                             '}';
        final JsonNode node = Json5.readTree(json5);
        assertThatJson(node).node("config.description")
                            .isEqualTo("Line 1 \\n                    Line 2");
    }

    @Test
    void trailingCommas() throws Exception {
        //language=JSON5
        final String json5 = "{\n" +
                             "  'objects': {\n" +
                             "    'a': 1,\n" +
                             "    'b': 2,\n" +
                             "  },\n" +
                             "  'arrays': ['item1', 'item2',],\n" +
                             '}';
        final JsonNode node = Json5.readTree(json5);
        assertThatJson(node).node("objects.a").isEqualTo(1);
        assertThatJson(node).node("objects.b").isEqualTo(2);
        assertThatJson(node).node("arrays").isArray().ofLength(2);
    }

    @Test
    void leadingDecimalPoint() throws Exception {
        //language=JSON5
        final String json5 = "{ 'number': .8675309 }";
        final JsonNode node = Json5.readTree(json5);
        assertThatJson(node).node("number").isEqualTo(0.8675309);
    }

    @Test
    void positiveSign() throws Exception {
        //language=JSON5
        final String json5 = "{ 'positive': +1, 'negative': -1 }";
        final JsonNode node = Json5.readTree(json5);
        assertThatJson(node).node("positive").isEqualTo(1);
        assertThatJson(node).node("negative").isEqualTo(-1);
    }

    @Test
    void backwardCompatibleWithJson() throws Exception {
        //language=JSON
        final String json = "{\n" +
                            "  \"key\": \"value\",\n" +
                            "  \"number\": 123,\n" +
                            "  \"array\": [1, 2, 3]\n" +
                            '}';
        final JsonNode node = Json5.readTree(json);
        assertThatJson(node).node("key").isEqualTo("value");
        assertThatJson(node).node("number").isEqualTo(123);
        assertThatJson(node).node("array").isArray().ofLength(3);
    }

    @Test
    void specialNumbers() throws Exception {
        //language=JSON5
        final String json5 = "{\n" +
                             "  'infinity': Infinity,\n" +
                             "  'negativeInfinity': -Infinity,\n" +
                             "  'notANumber': NaN\n" +
                             '}';
        final JsonNode node = Json5.readTree(json5);
        assertThat(node.get("infinity").isNumber()).isTrue();
        assertThat(node.get("infinity").doubleValue()).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(node.get("negativeInfinity").doubleValue()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(node.get("notANumber").doubleValue()).isNaN();
    }

    @Test
    void mixedQuotes() throws Exception {
        //language=JSON5
        final String json5 = "{\n" +
                             "  \"doubleQuoted\": \"value1\",\n" +
                             "  'singleQuoted': 'value2',\n" +
                             "  unquoted: 'value3'\n" +
                             '}';
        final JsonNode node = Json5.readTree(json5);
        assertThatJson(node).node("doubleQuoted").isEqualTo("value1");
        assertThatJson(node).node("singleQuoted").isEqualTo("value2");
        assertThatJson(node).node("unquoted").isEqualTo("value3");
    }

    @Test
    void nestedStructures() throws Exception {
        //language=JSON5
        final String json5 = "{\n" +
                             "  // Root level\n" +
                             "  outer: {\n" +
                             "    // Nested level\n" +
                             "    inner: {\n" +
                             "      value: 'deep',\n" +
                             "    },\n" +
                             "  },\n" +
                             "  array: [\n" +
                             "    { id: 1, },\n" +
                             "    { id: 2, },\n" +
                             "  ],\n" +
                             '}';
        final JsonNode node = Json5.readTree(json5);
        assertThatJson(node).node("outer.inner.value").isEqualTo("deep");
        assertThatJson(node).node("array[0].id").isEqualTo(1);
        assertThatJson(node).node("array[1].id").isEqualTo(2);
    }

    // Unsupported JSON5 Features

    @Test
    void hexadecimalIntegersNotSupported() {
        // Hexadecimal numbers are not supported (e.g., 0xdecaf)
        final String json5 = "{ 'hex': 0xdecaf }";
        assertThatThrownBy(() -> Json5.readTree(json5))
                .isInstanceOf(JsonParseException.class)
                .hasMessageContaining("Unexpected character ('x'");
    }

    @Test
    void trailingDecimalPointNotSupported() {
        // Trailing decimal points are not supported (e.g., 8675309.)
        final String json5 = "{ 'number': 8675309. }";
        assertThatThrownBy(() -> Json5.readTree(json5))
                .isInstanceOf(JsonParseException.class)
                .hasMessageContaining("Decimal point not followed by a digit");
    }

    @Test
    void trailingDecimalPointWithSignNotSupported() {
        // Trailing decimal point with sign
        final String json5 = "{ 'number': +123. }";
        assertThatThrownBy(() -> Json5.readTree(json5))
                .isInstanceOf(JsonParseException.class);
    }

    // File Type Detection

    @Test
    void isJson5() {
        assertThat(Json5.isJson5("/path/to/file.json5")).isTrue();
        assertThat(Json5.isJson5("/path/to/FILE.JSON5")).isTrue();
        assertThat(Json5.isJson5("config.json5")).isTrue();

        assertThat(Json5.isJson5("/path/to/file.json")).isFalse();
        assertThat(Json5.isJson5("/path/to/file.txt")).isFalse();
        assertThat(Json5.isJson5("file.json5.bak")).isFalse();
    }

    @Test
    void isJson() {
        assertThat(Json5.isJson("/path/to/file.json")).isTrue();
        assertThat(Json5.isJson("/path/to/FILE.JSON")).isTrue();
        assertThat(Json5.isJson("config.json")).isTrue();

        assertThat(Json5.isJson("/path/to/file.json5")).isFalse();
        assertThat(Json5.isJson("/path/to/file.txt")).isFalse();
        assertThat(Json5.isJson("file.json.bak")).isFalse();
    }

    @Test
    void isJsonCompatible() {
        // JSON files are JSON-compatible
        assertThat(Json5.isJsonCompatible("/path/to/file.json")).isTrue();
        assertThat(Json5.isJsonCompatible("config.JSON")).isTrue();

        // JSON5 files are JSON-compatible
        assertThat(Json5.isJsonCompatible("/path/to/file.json5")).isTrue();
        assertThat(Json5.isJsonCompatible("config.JSON5")).isTrue();

        // Other files are not JSON-compatible
        assertThat(Json5.isJsonCompatible("/path/to/file.txt")).isFalse();
        assertThat(Json5.isJsonCompatible("config.yaml")).isFalse();
        assertThat(Json5.isJsonCompatible("file.json.bak")).isFalse();
    }
}
