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

package com.linecorp.centraldogma.common;

import static com.linecorp.centraldogma.testing.internal.TestUtil.assertJsonConversion;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.internal.Jackson;

class ChangeSerializerTest {

    @Test
    void serializeTextUpsert() {
        final Change<String> change = Change.ofTextUpsert("/test.txt", "Hello, World!");
        assertJsonConversion(change, Change.class,
                             '{' +
                             "  \"type\": \"UPSERT_TEXT\"," +
                             "  \"path\": \"/test.txt\"," +
                             "  \"content\": \"Hello, World!\"" +
                             '}');
    }

    @Test
    void serializeJsonUpsertWithString() {
        final Change<JsonNode> change = Change.ofJsonUpsert("/config.json", "{ \"key\": \"value\" }");
        assertJsonConversion(change, Change.class,
                             '{' +
                             "  \"type\": \"UPSERT_JSON\"," +
                             "  \"path\": \"/config.json\"," +
                             "  \"rawContent\": \"{ \\\"key\\\": \\\"value\\\" }\"" +
                             '}');
    }

    @Test
    void serializeJsonUpsertWithJsonNode() {
        final JsonNode jsonNode = Jackson.valueToTree(new TestObject("test", 42));
        final Change<JsonNode> change = Change.ofJsonUpsert("/data.json", jsonNode);
        assertJsonConversion(change, Change.class,
                             '{' +
                             "  \"type\": \"UPSERT_JSON\"," +
                             "  \"path\": \"/data.json\"," +
                             "  \"content\": {" +
                             "    \"name\": \"test\"," +
                             "    \"value\": 42" +
                             "  }" +
                             '}');
    }

    @Test
    void serializeRemoval() {
        final Change<Void> change = Change.ofRemoval("/obsolete.txt");
        assertJsonConversion(change, Change.class,
                             '{' +
                             "  \"type\": \"REMOVE\"," +
                             "  \"path\": \"/obsolete.txt\"" +
                             '}');
    }

    @Test
    void serializeRename() {
        final Change<String> change = Change.ofRename("/old.txt", "/new.txt");
        assertJsonConversion(change, Change.class,
                             '{' +
                             "  \"type\": \"RENAME\"," +
                             "  \"path\": \"/old.txt\"," +
                             "  \"content\": \"/new.txt\"" +
                             '}');
    }

    @Test
    void serializeTextPatch() {
        final Change<String> change = Change.ofTextPatch("/file.txt", "old content", "new content");
        assertJsonConversion(change, Change.class,
                             '{' +
                             "  \"type\": \"APPLY_TEXT_PATCH\"," +
                             "  \"path\": \"/file.txt\"," +
                             "  \"content\": \"--- /file.txt\\n" +
                             "+++ /file.txt\\n" +
                             "@@ -1,1 +1,1 @@\\n" +
                             "-old content\\n" +
                             "+new content\"" +
                             '}');
    }

    @Test
    void serializeJsonPatchWithStrings() {
        final Change<JsonNode> change = Change.ofJsonPatch("/config.json",
                                                           "{ \"foo\": \"bar\" }",
                                                           "{ \"foo\": \"baz\" }");
        assertJsonConversion(change, Change.class,
                             '{' +
                             "  \"type\": \"APPLY_JSON_PATCH\"," +
                             "  \"path\": \"/config.json\"," +
                             "  \"content\": [{" +
                             "    \"op\": \"safeReplace\"," +
                             "    \"path\": \"/foo\"," +
                             "    \"oldValue\": \"bar\"," +
                             "    \"value\": \"baz\"" +
                             "  }]" +
                             '}');
    }

    @Test
    void serializeJsonPatchWithNodes() {
        final JsonNode oldNode = Jackson.valueToTree(new TestObject("old", 1));
        final JsonNode newNode = Jackson.valueToTree(new TestObject("new", 2));
        final Change<JsonNode> change = Change.ofJsonPatch("/data.json", oldNode, newNode);
        assertJsonConversion(change, Change.class,
                             '{' +
                             "  \"type\": \"APPLY_JSON_PATCH\"," +
                             "  \"path\": \"/data.json\"," +
                             "  \"content\": [" +
                             "    {" +
                             "      \"op\": \"safeReplace\"," +
                             "      \"path\": \"/name\"," +
                             "      \"oldValue\": \"old\"," +
                             "      \"value\": \"new\"" +
                             "    }," +
                             "    {" +
                             "      \"op\": \"safeReplace\"," +
                             "      \"path\": \"/value\"," +
                             "      \"oldValue\": 1," +
                             "      \"value\": 2" +
                             "    }" +
                             "  ]" +
                             '}');
    }

    @Test
    void serializeJsonUpsertWithComplexObject() {
        final JsonNode complexNode = Jackson.valueToTree(
                new ComplexObject("item", new TestObject("nested", 100)));
        final Change<JsonNode> change = Change.ofJsonUpsert("/complex.json", complexNode);
        assertJsonConversion(change, Change.class,
                             '{' +
                             "  \"type\": \"UPSERT_JSON\"," +
                             "  \"path\": \"/complex.json\"," +
                             "  \"content\": {" +
                             "    \"id\": \"item\"," +
                             "    \"data\": {" +
                             "      \"name\": \"nested\"," +
                             "      \"value\": 100" +
                             "    }" +
                             "  }" +
                             '}');
    }

    @Test
    void serializeJsonUpsertWithArrayFromString() {
        // Test JSON array created from string uses 'rawContent'
        final Change<JsonNode> change = Change.ofJsonUpsert("/array.json", "[1, 2, 3]");
        assertJsonConversion(change, Change.class,
                             '{' +
                             "  \"type\": \"UPSERT_JSON\"," +
                             "  \"path\": \"/array.json\"," +
                             "  \"rawContent\": \"[1, 2, 3]\"" +
                             '}');
    }

    @Test
    void serializeJsonUpsertWithArrayNode() {
        // Test JSON array created from JsonNode uses 'content'
        final JsonNode arrayNode = Jackson.valueToTree(new int[]{1, 2, 3});
        final Change<JsonNode> change = Change.ofJsonUpsert("/array.json", arrayNode);
        assertJsonConversion(change, Change.class,
                             '{' +
                             "  \"type\": \"UPSERT_JSON\"," +
                             "  \"path\": \"/array.json\"," +
                             "  \"content\": [1, 2, 3]" +
                             '}');
    }

    @Test
    void serializeEmptyTextContent() {
        // Test empty text content
        final Change<String> change = Change.ofTextUpsert("/empty.txt", "");
        assertJsonConversion(change, Change.class,
                             '{' +
                             "  \"type\": \"UPSERT_TEXT\"," +
                             "  \"path\": \"/empty.txt\"," +
                             "  \"content\": \"\"" +
                             '}');
    }

    private static class TestObject {
        private String name;
        private int value;

        TestObject(String name, int value) {
            this.name = name;
            this.value = value;
        }

        @JsonProperty
        String getName() {
            return name;
        }

        @JsonProperty
        int getValue() {
            return value;
        }
    }

    private static class ComplexObject {
        private String id;
        private TestObject data;

        ComplexObject(String id, TestObject data) {
            this.id = id;
            this.data = data;
        }

        @JsonProperty
        String getId() {
            return id;
        }

        @JsonProperty
        TestObject getData() {
            return data;
        }
    }
}
