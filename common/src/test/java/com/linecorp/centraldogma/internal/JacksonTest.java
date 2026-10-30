/*
 * Copyright 2020 LINE Corporation
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

import static com.linecorp.centraldogma.internal.Jackson.readTree;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.QueryExecutionException;

class JacksonTest {

    @Test
    void nullCanBeAnyTypeWhileMerging() throws IOException {
        final JsonNode nullNode = readTree("{\"a\": null}");
        final JsonNode numberNode = readTree("{\"a\": 1}");
        JsonNode merged = Jackson.mergeTree(nullNode, numberNode);
        assertThatJson(merged).isEqualTo("{\"a\": 1}");

        final JsonNode stringNode = readTree("{\"a\": \"foo\"}");
        merged = Jackson.mergeTree(nullNode, stringNode);
        assertThatJson(merged).isEqualTo("{\"a\": \"foo\"}");

        final JsonNode arrayNode = readTree("{\"a\": [1, 2, 3]}");
        merged = Jackson.mergeTree(nullNode, arrayNode);
        assertThatJson(merged).isEqualTo("{\"a\": [1, 2, 3]}");

        final JsonNode objectNode = readTree('{' +
                                             "   \"a\": {" +
                                             "      \"b\": \"foo\"" +
                                             "   }" +
                                             '}');
        merged = Jackson.mergeTree(nullNode, objectNode);
        assertThatJson(merged).isEqualTo('{' +
                                         "   \"a\": {" +
                                         "      \"b\": \"foo\"" +
                                         "   }" +
                                         '}');
    }

    @Test
    void rootShouldBeObjectNode() throws IOException {
        final JsonNode arrayJson1 = readTree("[1, 2, 3]");
        final JsonNode arrayJson2 = readTree("[3, 4, 5]");

        assertThatThrownBy(() -> Jackson.mergeTree(arrayJson1, arrayJson2))
                .isExactlyInstanceOf(QueryExecutionException.class)
                .hasMessageContaining("/ type: ARRAY (expected: OBJECT)");
    }

    @Test
    void mergeMixedJsonNodeTypes() throws IOException {
        final JsonNode baseJson = readTree('{' +
                                           "   \"a\": \"foo1\"," +
                                           "   \"b\": \"foo2\"," +
                                           "   \"d\": {" +
                                           "      \"e\": 1," +
                                           "      \"f\": 2," +
                                           "      \"h\": {" +
                                           "         \"i\": [\"bar1\", \"bar2\"]," +
                                           "         \"j\": null" +
                                           "      }" +
                                           "   }" +
                                           '}');

        final JsonNode additionalJson = readTree('{' +
                                                 "   \"a\": \"foo3\"," +
                                                 "   \"d\": {" +
                                                 "      \"e\": null," +
                                                 "      \"h\": {" +
                                                 "         \"i\": [\"bar3\", \"bar4\"]" +
                                                 "      }" +
                                                 "   }" +
                                                 '}');

        final JsonNode additionalJson1 = readTree('{' +
                                                  "   \"c\": \"foo4\"," +
                                                  "   \"d\": {" +
                                                  "      \"g\": 4," +
                                                  "      \"h\": {" +
                                                  "         \"j\": [\"bar5\", \"bar6\"]" +
                                                  "      }" +
                                                  "   }" +
                                                  '}');

        final JsonNode merged = Jackson.mergeTree(baseJson, additionalJson, additionalJson1);
        final String expectedJson = '{' +
                                    "   \"a\": \"foo3\"," +
                                    "   \"b\": \"foo2\"," +
                                    "   \"c\": \"foo4\"," +
                                    "   \"d\": {" +
                                    "      \"e\": null," +
                                    "      \"f\": 2," +
                                    "      \"g\": 4," +
                                    "      \"h\": {" +
                                    "         \"i\": [\"bar3\", \"bar4\"]," +
                                    "         \"j\": [\"bar5\", \"bar6\"]" +
                                    "      }" +
                                    "   }" +
                                    '}';
        assertThatJson(merged).isEqualTo(expectedJson);
    }

    @Test
    void mismatchedValueNodeWhileMerging() throws IOException {
        final JsonNode baseJson = readTree('{' +
                                           "   \"a\": {" +
                                           "      \"b\": \"foo\"" +
                                           "   }" +
                                           '}');
        final JsonNode arrayJson = readTree("[1, 2, 3]");
        assertThatThrownBy(() -> Jackson.mergeTree(baseJson, arrayJson))
                .isExactlyInstanceOf(QueryExecutionException.class)
                .hasMessageContaining("/ type: ARRAY (expected: OBJECT)");

        final JsonNode numberJson = readTree('{' +
                                             "   \"a\": {" +
                                             "      \"b\": 3" +
                                             "   }" +
                                             '}');
        assertThatThrownBy(() -> Jackson.mergeTree(baseJson, numberJson))
                .isExactlyInstanceOf(QueryExecutionException.class)
                .hasMessageContaining("/a/b/ type: NUMBER (expected: STRING)");

        final JsonNode objectArrayJson = readTree('{' +
                                                  "   \"a\": [\"b\", \"c\"]" +
                                                  '}');
        assertThatThrownBy(() -> Jackson.mergeTree(baseJson, objectArrayJson))
                .isExactlyInstanceOf(QueryExecutionException.class)
                .hasMessageContaining("/a/ type: ARRAY (expected: OBJECT)");

        final JsonNode nullJson = readTree('{' +
                                           "   \"a\": null" +
                                           '}');
        assertThatThrownBy(() -> Jackson.mergeTree(baseJson, nullJson, numberJson))
                .isExactlyInstanceOf(QueryExecutionException.class)
                .hasMessageContaining("/a/b/ type: NUMBER (expected: STRING)");
    }

    @Test
    void coercionTest() throws Exception {
        //language=JSON
        final String json = '{' +
                            "  \"aa\":\"123\"," +
                            "  \"bb\":\"true\"," +
                            "  \"cc\":\"hello\"" +
                            '}';
        final Foo foo = Jackson.readValue(json, Foo.class);
        assertThat(foo.aa).isEqualTo(123);
        assertThat(foo.bb).isEqualTo(true);
        assertThat(foo.cc).isEqualTo("hello");
    }

    @Test
    void extractKeysWithTilde() throws IOException {
        final JsonNode node = readTree('{' +
                                       "   \"a\": 1," +
                                       "   \"b\": {" +
                                       "      \"c\": 2," +
                                       "      \"d\": 3" +
                                       "   }," +
                                       "   \"e\": [10, 20]," +
                                       "   \"f\": {}" +
                                       '}');
        assertThatJson(Jackson.extractTree(node, "$.*~")).isEqualTo("[\"a\", \"b\", \"e\", \"f\"]");
        assertThatJson(Jackson.extractTree(node, "$.b.*~")).isEqualTo("[\"c\", \"d\"]");
        assertThatJson(Jackson.extractTree(node, "$.b[*]~")).isEqualTo("[\"c\", \"d\"]");
        assertThatJson(Jackson.extractTree(node, "$.e.*~")).isEqualTo("[0, 1]");
        assertThatJson(Jackson.extractTree(node, "$.f.*~")).isEqualTo("[]");
        assertThatJson(Jackson.extractTree(node, "$..*~"))
                .isEqualTo("[\"a\", \"b\", \"e\", \"f\", \"c\", \"d\", 0, 1]");
        assertThatJson(Jackson.extractTree(node, "$.b~")).isEqualTo("\"b\"");
        assertThatJson(Jackson.extractTree(node, "$['b']~")).isEqualTo("\"b\"");
        assertThatJson(Jackson.extractTree(node, "$.e[1]~")).isEqualTo("1");
        assertThatJson(Jackson.extractTree(node, ImmutableList.of("$.b", "$.*~")))
                .isEqualTo("[\"c\", \"d\"]");

        // The values are returned as before if '~' is not used.
        assertThatJson(Jackson.extractTree(node, "$.b.*")).isEqualTo("[2, 3]");
        assertThatJson(Jackson.extractTree(node, "$.f.*")).isEqualTo("[]");
        assertThatThrownBy(() -> Jackson.extractTree(node, "$~"))
                .isExactlyInstanceOf(QueryExecutionException.class);
    }

    @Test
    void extractKeysWithSpecialCharactersWithTilde() throws IOException {
        final JsonNode node = readTree("{\"a'b\": 1, \"c]d\": 2, \"e.f\": 3, \"g~\": 4," +
                                       " \"h['i\": { \"j']['k\": 5 }, \"l', 'm\": { \"n\": 6 }}");
        assertThatJson(Jackson.extractTree(node, "$.*~"))
                .isEqualTo("[\"a'b\", \"c]d\", \"e.f\", \"g~\", \"h['i\", \"l', 'm\"]");
        assertThatJson(Jackson.extractTree(node, "$.*.*~")).isEqualTo("[\"j']['k\", \"n\"]");
        assertThatJson(Jackson.extractTree(node, "$['g~']")).isEqualTo("4");
    }

    @Test
    void extractKeysOfMultiplePropertiesWithTilde() throws IOException {
        final JsonNode node = readTree('{' +
                                       "   \"a\": 1," +
                                       "   \"a'b\": 2," +
                                       "   \"c\": { \"x\": 3, \"y\": 4 }," +
                                       "   \"d\": { \"x\": 5 }," +
                                       "   \"e\": {}" +
                                       '}');
        assertThatJson(Jackson.extractTree(node, "$['a','c']~")).isEqualTo("[\"a\", \"c\"]");
        assertThatJson(Jackson.extractTree(node, "$['a','a\\'b']~")).isEqualTo("[\"a\", \"a'b\"]");
        // Only the properties which exist are returned, like the values.
        assertThatJson(Jackson.extractTree(node, "$['a','z']~")).isEqualTo("[\"a\"]");
        assertThatJson(Jackson.extractTree(node, "$['z','w']~")).isEqualTo("[]");
        assertThatJson(Jackson.extractTree(node, "$.c['x','y']~")).isEqualTo("[\"x\", \"y\"]");
        assertThatJson(Jackson.extractTree(node, "$.*['x','y']~")).isEqualTo("[\"x\", \"y\", \"x\"]");
        assertThatJson(Jackson.extractTree(node, "$..['x','y']~")).isEqualTo("[\"x\", \"y\"]");
        assertThatJson(Jackson.extractTree(node, "$[ 'a', 'c' ]~")).isEqualTo("[\"a\", \"c\"]");
        assertThatJson(Jackson.extractTree(node, "$['c','a','c']~")).isEqualTo("[\"c\", \"a\"]");
    }

    @Test
    void extractKeysWhenNamesLookLikePathsWithTilde() throws IOException {
        // json-path does not escape the names in the matched paths, e.g. $['a']['b'] for the key "a']['b".
        final JsonNode node = readTree("{\"a\": {}, \"a']['b\": 1, \"a'][0\": 2, \"b\": 3, \"a', 'b\": 4}");
        assertThatJson(Jackson.extractTree(node, "$.*~"))
                .isEqualTo("[\"a\", \"a']['b\", \"a'][0\", \"b\", \"a', 'b\"]");
        assertThatJson(Jackson.extractTree(node, "$['a','b']~")).isEqualTo("[\"a\", \"b\"]");
    }

    static final class Foo {
        final int aa;
        final boolean bb;
        final String cc;

        @JsonCreator
        private Foo(@JsonProperty("aa") int aa, @JsonProperty("bb") boolean bb, @JsonProperty("cc") String cc) {
            this.aa = aa;
            this.bb = bb;
            this.cc = cc;
        }
    }
}
