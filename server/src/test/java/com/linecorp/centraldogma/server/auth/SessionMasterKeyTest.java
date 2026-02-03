/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.internal.Jackson;

class SessionMasterKeyTest {

    @Test
    void serialize() throws Exception {
        final Instant creationTime = Instant.parse("2025-01-15T10:00:00Z");
        final SessionMasterKey key = new SessionMasterKey(
                "wrappedKey123",
                1,
                "salt123",
                "kek-id-1",
                creationTime
        );

        final String json = Jackson.writeValueAsString(key);
        final JsonNode node = Jackson.readTree(json);

        assertThat(node.get("wrappedMasterKey").asText()).isEqualTo("wrappedKey123");
        assertThat(node.get("version").asInt()).isEqualTo(1);
        assertThat(node.get("salt").asText()).isEqualTo("salt123");
        assertThat(node.get("kekId").asText()).isEqualTo("kek-id-1");
        assertThat(node.get("creation").asText()).isEqualTo("2025-01-15T10:00:00Z");
    }

    @Test
    void deserialize() throws Exception {
        final String json = '{' +
                            "  \"wrappedMasterKey\": \"wrappedKey456\"," +
                            "  \"version\": 2," +
                            "  \"salt\": \"salt456\"," +
                            "  \"kekId\": \"kek-id-2\"," +
                            "  \"creation\": \"2025-02-20T15:30:45Z\"" +
                            '}';

        final SessionMasterKey key = Jackson.readValue(json, SessionMasterKey.class);
        assertThat(key.wrappedMasterKey()).isEqualTo("wrappedKey456");
        assertThat(key.version()).isEqualTo(2);
        assertThat(key.salt()).isEqualTo("salt456");
        assertThat(key.kekId()).isEqualTo("kek-id-2");
        assertThat(key.creation()).isEqualTo("2025-02-20T15:30:45Z");
    }

    @Test
    void serializeAndDeserialize() throws Exception {
        final SessionMasterKey original = new SessionMasterKey(
                "wrappedKey789",
                3,
                "salt789",
                "kek-id-3",
                Instant.parse("2025-03-25T08:15:30Z")
        );

        final String json = Jackson.writeValueAsString(original);
        final SessionMasterKey deserialized = Jackson.readValue(json, SessionMasterKey.class);

        assertThat(deserialized.wrappedMasterKey()).isEqualTo(original.wrappedMasterKey());
        assertThat(deserialized.version()).isEqualTo(original.version());
        assertThat(deserialized.salt()).isEqualTo(original.salt());
        assertThat(deserialized.kekId()).isEqualTo(original.kekId());
        assertThat(deserialized.creation()).isEqualTo(original.creation());
    }
}
