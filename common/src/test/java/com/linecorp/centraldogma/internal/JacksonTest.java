/*
 * Copyright 2018 LINE Corporation
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

import static com.linecorp.centraldogma.internal.Jackson.mergeJsonNodes;
import static com.linecorp.centraldogma.internal.Jackson.readTree;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

public class JacksonTest {

    @Test
    public void nullCanBeAnyTypeWhileMerging() throws IOException {
        final JsonNode nullNode = readTree("{\"a\": null}");
        final JsonNode numberNode = readTree("{\"a\": 1}");
        JsonNode merged = mergeJsonNodes(nullNode, numberNode);
        assertThatJson(merged).isEqualTo("{\"a\": 1}");

        final JsonNode stringNode = readTree("{\"a\": \"foo\"}");
        merged = mergeJsonNodes(nullNode, stringNode);
        assertThatJson(merged).isEqualTo("{\"a\": \"foo\"}");

        final JsonNode arrayNode = readTree("{\"a\": [1, 2, 3]}");
        merged = mergeJsonNodes(nullNode, arrayNode);
        assertThatJson(merged).isEqualTo("{\"a\": [1, 2, 3]}");

        final JsonNode objectNode = readTree('{' +
                                             "   \"a\": {" +
                                             "      \"b\": \"foo\"" +
                                             "   }" +
                                             '}');
        merged = mergeJsonNodes(nullNode, objectNode);
        assertThatJson(merged).isEqualTo('{' +
                                         "   \"a\": {" +
                                         "      \"b\": \"foo\"" +
                                         "   }" +
                                         '}');
    }

    @Test
    public void arrayNodeIsReplacedWhileMerging() throws IOException {
        final JsonNode arrayJson1 = readTree("[1, 2, 3]");
        final JsonNode arrayJson2 = readTree("[3, 4, 5]");

        final JsonNode merged = mergeJsonNodes(arrayJson1, arrayJson2);
        final String expectedJson = "[3, 4, 5]";
        assertThatJson(merged).isEqualTo(expectedJson);
    }

    @Test
    public void mergeMixedJsonNodeTypes() throws IOException {
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

        final JsonNode merged = mergeJsonNodes(baseJson, additionalJson, additionalJson1);
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
    public void mismatchedValueNodeWhileMerging() throws IOException {
        final JsonNode baseJson = readTree('{' +
                                           "   \"a\": {" +
                                           "      \"b\": \"foo\"" +
                                           "   }" +
                                           '}');
        final JsonNode arrayJson = readTree("[1, 2, 3]");
        assertThatThrownBy(() -> mergeJsonNodes(baseJson, arrayJson))
                .isExactlyInstanceOf(MismatchedValueException.class)
                .hasMessage("/ type: ARRAY (expected: OBJECT)");

        final JsonNode numberJson = readTree('{' +
                                             "   \"a\": {" +
                                             "      \"b\": 3" +
                                             "   }" +
                                             '}');
        assertThatThrownBy(() -> mergeJsonNodes(baseJson, numberJson))
                .isExactlyInstanceOf(MismatchedValueException.class)
                .hasMessage("/a/b/ type: NUMBER (expected: STRING)");

        final JsonNode objectArrayJson = readTree('{' +
                                                  "   \"a\": [\"b\", \"c\"]" +
                                                  '}');
        assertThatThrownBy(() -> mergeJsonNodes(baseJson, objectArrayJson))
                .isExactlyInstanceOf(MismatchedValueException.class)
                .hasMessage("/a/ type: ARRAY (expected: OBJECT)");

        final JsonNode nullJson = readTree('{' +
                                           "   \"a\": null" +
                                           '}');
        assertThatThrownBy(() -> mergeJsonNodes(baseJson, nullJson, numberJson))
                .isExactlyInstanceOf(MismatchedValueException.class)
                .hasMessage("/a/b/ type: NUMBER (expected: STRING)");
    }
}
