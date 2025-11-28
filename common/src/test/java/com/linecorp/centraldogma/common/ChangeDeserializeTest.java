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

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.internal.common.JacksonUtil;

class ChangeDeserializeTest {

    @Test
    void deserializeContentAsJson() throws JsonProcessingException {
        String json =
                "{ \"type\": \"UPSERT_JSON\", \"path\": \"/foo.json\", \"content\": { \"key\": \"value\" } }";
        final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();
        final Change<JsonNode> change = mapper.readValue(json, Change.class);
        assertThat(change.type()).isEqualTo(ChangeType.UPSERT_JSON);
        assertThat(change.path()).isEqualTo("/foo.json");
        assertThat(change.content()).isInstanceOf(ObjectNode.class);
        assertThat(change.content().get("key").asText()).isEqualTo("value");
    }

    @Test
    void deserializeRawContentAsJson() throws JsonProcessingException {
        String json =
                "{ \"type\": \"UPSERT_JSON\", \"path\": \"/foo.json\", \"rawContent\": \"{ \\\"key\\\": \\\"value\\\" }\" }";
        final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();
        final Change<JsonNode> change = mapper.readValue(json, Change.class);
        assertThat(change.type()).isEqualTo(ChangeType.UPSERT_JSON);
        assertThat(change.path()).isEqualTo("/foo.json");
        assertThat(change.content()).isInstanceOf(ObjectNode.class);
        assertThat(change.content().get("key").asText()).isEqualTo("value");
    }
}
