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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.centraldogma.internal.Jackson;

class ChangeDeserializeTest {

    @Test
    void deserializeContentAsJson() throws JsonProcessingException {
        final String json =
                "{ \"type\": \"UPSERT_JSON\", \"path\": \"/foo.json\", \"content\": { \"key\": \"value\" } }";
        final Change<JsonNode> change = Jackson.readValue(json, Change.class);
        assertThat(change.type()).isEqualTo(ChangeType.UPSERT_JSON);
        assertThat(change.path()).isEqualTo("/foo.json");
        assertThat(change.content()).isInstanceOf(ObjectNode.class);
        assertThat(change.content().get("key").asText()).isEqualTo("value");
        assertThat(change.rawContent()).isNull();
    }

    @Test
    void deserializeRawContentAsJson() throws JsonProcessingException {
        final String json =
                "{ \"type\": \"UPSERT_JSON\", \"path\": \"/foo.json\", " +
                "\"rawContent\": \"{ \\\"key\\\": \\\"value\\\" }\" }";
        final Change<JsonNode> change = Jackson.readValue(json, Change.class);
        assertThat(change.type()).isEqualTo(ChangeType.UPSERT_JSON);
        assertThat(change.path()).isEqualTo("/foo.json");
        assertThat(change.content()).isInstanceOf(ObjectNode.class);
        assertThat(change.content().get("key").asText()).isEqualTo("value");
        assertThat(change.rawContent()).isEqualTo("{ \"key\": \"value\" }");
    }

    @Test
    void deserializeUpsertTextWithContent() throws JsonProcessingException {
        final String json =
                "{ \"type\": \"UPSERT_TEXT\", \"path\": \"/foo.txt\", \"content\": \"Hello, World!\" }";
        final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();
        final Change<String> change = Jackson.readValue(json, Change.class);
        assertThat(change.type()).isEqualTo(ChangeType.UPSERT_TEXT);
        assertThat(change.path()).isEqualTo("/foo.txt");
        assertThat(change.content()).isEqualTo("Hello, World!");
    }

    @Test
    void deserializeUpsertTextWithRawContent() throws JsonProcessingException {
        final String json =
                "{ \"type\": \"UPSERT_TEXT\", \"path\": \"/foo.txt\", \"rawContent\": \"Hello, World!\" }";
        final Change<String> change = Jackson.readValue(json, Change.class);
        assertThat(change.type()).isEqualTo(ChangeType.UPSERT_TEXT);
        assertThat(change.path()).isEqualTo("/foo.txt");
        assertThat(change.content()).isEqualTo("Hello, World!");
        assertThat(change.rawContent()).isEqualTo("Hello, World!");
    }

    @Test
    void deserializeRemove() throws JsonProcessingException {
        final String json = "{ \"type\": \"REMOVE\", \"path\": \"/foo.txt\" }";
        final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();
        final Change<Void> change = Jackson.readValue(json, Change.class);
        assertThat(change.type()).isEqualTo(ChangeType.REMOVE);
        assertThat(change.path()).isEqualTo("/foo.txt");
        assertThat(change.content()).isNull();
        assertThat(change.rawContent()).isNull();
    }

    @Test
    void deserializeRename() throws JsonProcessingException {
        final String json =
                "{ \"type\": \"RENAME\", \"path\": \"/old.txt\", \"content\": \"/new.txt\" }";
        final Change<String> change = Jackson.readValue(json, Change.class);
        assertThat(change.type()).isEqualTo(ChangeType.RENAME);
        assertThat(change.path()).isEqualTo("/old.txt");
        assertThat(change.content()).isEqualTo("/new.txt");
        assertThat(change.rawContent()).isEqualTo("/new.txt");
    }

    @Test
    void deserializeApplyJsonPatchWithContent() throws JsonProcessingException {
        final String json =
                "{ \"type\": \"APPLY_JSON_PATCH\", \"path\": \"/foo.json\", " +
                "\"content\": [{ \"op\": \"replace\", \"path\": \"/key\", \"value\": \"newValue\" }] }";
        final Change<JsonNode> change = Jackson.readValue(json, Change.class);
        assertThat(change.type()).isEqualTo(ChangeType.APPLY_JSON_PATCH);
        assertThat(change.path()).isEqualTo("/foo.json");
        assertThat(change.content()).isNotNull();
        assertThat(change.content().isArray()).isTrue();
        assertThat(change.content().get(0).get("op").asText()).isEqualTo("replace");
        assertThat(change.content().get(0).get("path").asText()).isEqualTo("/key");
        assertThat(change.content().get(0).get("value").asText()).isEqualTo("newValue");
    }

    @Test
    void deserializeApplyJsonPatchWithRawContent() throws JsonProcessingException {
        final String json =
                "{ \"type\": \"APPLY_JSON_PATCH\", \"path\": \"/foo.json\", " +
                "\"rawContent\": \"[{ \\\"op\\\": \\\"replace\\\", \\\"path\\\": \\\"/key\\\", " +
                "\\\"value\\\": \\\"newValue\\\" }]\" }";
        final Change<JsonNode> change = Jackson.readValue(json, Change.class);
        assertThat(change.type()).isEqualTo(ChangeType.APPLY_JSON_PATCH);
        assertThat(change.path()).isEqualTo("/foo.json");
        assertThat(change.content()).isNotNull();
        assertThat(change.content().isArray()).isTrue();
        assertThat(change.content().get(0).get("op").asText()).isEqualTo("replace");
        assertThat(change.content().get(0).get("path").asText()).isEqualTo("/key");
        assertThat(change.content().get(0).get("value").asText()).isEqualTo("newValue");
    }

    @Test
    void deserializeApplyTextPatch() throws JsonProcessingException {
        final String patchContent = "--- /file.txt\\n+++ /file.txt\\n@@ -1,1 +1,1 @@\\n-old\\n+new";
        final String json =
                "{ \"type\": \"APPLY_TEXT_PATCH\", \"path\": \"/file.txt\", \"content\": \"" +
                patchContent + "\" }";
        final Change<String> change = Jackson.readValue(json, Change.class);
        assertThat(change.type()).isEqualTo(ChangeType.APPLY_TEXT_PATCH);
        assertThat(change.path()).isEqualTo("/file.txt");
        assertThat(change.content()).isEqualTo(patchContent.replace("\\n", "\n"));
    }

    @Test
    void deserializeJsonWithBothContentAndRawContent() throws JsonProcessingException {
        final String json =
                "{ \"type\": \"UPSERT_JSON\", \"path\": \"/foo.json\", " +
                "\"content\": { \"key\": \"fromContent\" }, " +
                "\"rawContent\": \"{ \\\"key\\\": \\\"fromRawContent\\\" }\" }";

        assertThatThrownBy(() -> {
            Jackson.readValue(json, Change.class);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("only one of content or rawContent must be set for type: UPSERT_JSON");
    }

    @Test
    void deserializeJsonWithNestedStructure() throws JsonProcessingException {
        final String json =
                "{ \"type\": \"UPSERT_JSON\", \"path\": \"/foo.json\", " +
                "\"content\": { \"nested\": { \"key\": \"value\" }, \"array\": [1, 2, 3] } }";
        final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();
        final Change<JsonNode> change = Jackson.readValue(json, Change.class);
        assertThat(change.type()).isEqualTo(ChangeType.UPSERT_JSON);
        assertThat(change.path()).isEqualTo("/foo.json");
        assertThat(change.content().get("nested").get("key").asText()).isEqualTo("value");
        assertThat(change.content().get("array").isArray()).isTrue();
        assertThat(change.content().get("array").size()).isEqualTo(3);
        assertThat(change.content().get("array").get(0).asInt()).isEqualTo(1);
    }

    @Test
    void deserializeMultilineTextContent() throws JsonProcessingException {
        final String json =
                "{ \"type\": \"UPSERT_TEXT\", \"path\": \"/foo.txt\", " +
                "\"content\": \"Line 1\\nLine 2\\nLine 3\" }";
        final Change<String> change = Jackson.readValue(json, Change.class);
        assertThat(change.type()).isEqualTo(ChangeType.UPSERT_TEXT);
        assertThat(change.path()).isEqualTo("/foo.txt");
        assertThat(change.content()).isEqualTo("Line 1\nLine 2\nLine 3");
    }
}
