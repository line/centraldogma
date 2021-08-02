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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.JacksonYAMLParseException;

import com.linecorp.centraldogma.common.EntryType;
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
    void readTreeFailsOnWrongEntryType() throws JsonParseException {
        assertThatThrownBy(() -> readTree("foo: 123", EntryType.JSON))
                .isExactlyInstanceOf(JsonParseException.class);
        // Please add JSON string which is not interpreted as YAML
    }
}
