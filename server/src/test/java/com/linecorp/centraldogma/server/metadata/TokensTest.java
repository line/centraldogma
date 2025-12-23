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
package com.linecorp.centraldogma.server.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;

class TokensTest {

    private static final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void nullCertificateIds() {
        final UserAndTimestamp creation = UserAndTimestamp.of(Author.SYSTEM);
        final Token token = new Token("app-id-1", "appToken-secret-1", false, null, creation, null, null);
        final ImmutableMap<String, AppIdentity> appIds = ImmutableMap.of("app-id-1", token);
        final ImmutableMap<String, String> secrets = ImmutableMap.of("appToken-secret-1", "app-id-1");

        final Tokens tokens = new Tokens(appIds, secrets, null);

        assertThat(tokens.appIds()).isEqualTo(appIds);
        assertThat(tokens.secrets()).isEqualTo(secrets);
        assertThat(tokens.certificateIds()).isNotNull();
        assertThat(tokens.certificateIds()).isEmpty();
    }

    @Test
    void withCertificateIds() {
        final UserAndTimestamp creation = UserAndTimestamp.of(Author.SYSTEM);
        final Token token = new Token("app-id-1", "appToken-secret-1", false, null, creation, null, null);
        final CertificateAppIdentity certAppIdentity =
                new CertificateAppIdentity("app-id-2", "cert-id", false, false, creation);
        final ImmutableMap<String, AppIdentity> appIds = ImmutableMap.of("app-id-1", token,
                                                                         "app-id-2", certAppIdentity);
        final ImmutableMap<String, String> secrets = ImmutableMap.of("appToken-secret-1", "app-id-1");
        final ImmutableMap<String, String> certificateIds = ImmutableMap.of("cert-id", "app-id-2");

        final Tokens tokens = new Tokens(appIds, secrets, certificateIds);

        assertThat(tokens.appIds()).isEqualTo(appIds);
        assertThat(tokens.secrets()).isEqualTo(secrets);
        assertThat(tokens.certificateIds()).isEqualTo(certificateIds);
    }

    @Test
    void deserializationWithoutCertificateIds() throws Exception {
        // legacy format
        final String legacyJson = "{\n" +
                                  "  \"appIds\": {\n" +
                                  "    \"app-id-1\": {\n" +
                                  "      \"appId\": \"app-id-1\",\n" +
                                  "      \"secret\": \"appToken-secret-1\",\n" +
                                  "      \"systemAdmin\": false,\n" +
                                  "      \"allowGuestAccess\": true,\n" +
                                  "      \"creation\": {\n" +
                                  "        \"user\": \"test@test.com\",\n" +
                                  "        \"timestamp\": \"2025-12-23T00:00:00.000Z\"\n" +
                                  "      }\n" +
                                  "    }\n" +
                                  "  },\n" +
                                  "  \"secrets\": {\n" +
                                  "    \"appToken-secret-1\": \"app-id-1\"\n" +
                                  "  }\n" +
                                  '}';

        final Tokens tokens = mapper.readValue(legacyJson, Tokens.class);

        assertThat(tokens.appIds()).hasSize(1);
        assertThat(tokens.secrets()).hasSize(1);
        assertThat(tokens.certificateIds()).isNotNull();
        assertThat(tokens.certificateIds()).isEmpty();
    }

    @Test
    void deserializationWithCertificateIds() throws Exception {
        final String newJson = "{\n" +
                               "  \"appIds\": {\n" +
                               "    \"app-id-1\": {\n" +
                               "      \"type\": \"TOKEN\",\n" +
                               "      \"appId\": \"app-id-1\",\n" +
                               "      \"secret\": \"appToken-secret-1\",\n" +
                               "      \"systemAdmin\": false,\n" +
                               "      \"allowGuestAccess\": true,\n" +
                               "      \"creation\": {\n" +
                               "        \"user\": \"test@test.com\",\n" +
                               "        \"timestamp\": \"2025-12-23T00:00:00.000Z\"\n" +
                               "      }\n" +
                               "    },\n" +
                               "    \"app-id-2\": {\n" +
                               "      \"type\": \"CERTIFICATE\",\n" +
                               "      \"appId\": \"app-id-2\",\n" +
                               "      \"certificateId\": \"cert-id\",\n" +
                               "      \"systemAdmin\": false,\n" +
                               "      \"allowGuestAccess\": true,\n" +
                               "      \"creation\": {\n" +
                               "        \"user\": \"test@test.com\",\n" +
                               "        \"timestamp\": \"2025-12-23T00:00:00.000Z\"\n" +
                               "      }\n" +
                               "    }\n" +
                               "  },\n" +
                               "  \"secrets\": {\n" +
                               "    \"appToken-secret-1\": \"app-id-1\"\n" +
                               "  },\n" +
                               "  \"certificateIds\": {\n" +
                               "    \"cert-id\": \"app-id-2\"\n" +
                               "  }\n" +
                               '}';

        // When: Deserializing the new JSON
        final Tokens tokens = mapper.readValue(newJson, Tokens.class);

        assertThat(tokens.appIds()).hasSize(2);
        assertThat(tokens.secrets()).hasSize(1);
        assertThat(tokens.certificateIds()).hasSize(1);
        assertThat(tokens.certificateIds().get("cert-id")).isEqualTo("app-id-2");
    }

    @Test
    void serializationIncludesCertificateIds() throws Exception {
        final UserAndTimestamp creation = UserAndTimestamp.of(Author.SYSTEM);
        final Token token = new Token("app-id-1", "appToken-secret-1", false, null, creation, null, null);
        final CertificateAppIdentity certAppIdentity =
                new CertificateAppIdentity("app-id-2", "cert-id", false, false, creation);
        final ImmutableMap<String, AppIdentity> appIds = ImmutableMap.of("app-id-1", token,
                                                                         "app-id-2", certAppIdentity);
        final ImmutableMap<String, String> secrets = ImmutableMap.of("appToken-secret-1", "app-id-1");
        final ImmutableMap<String, String> certificateIds = ImmutableMap.of("cert-id", "app-id-2");
        final Tokens tokens = new Tokens(appIds, secrets, certificateIds);

        final String json = mapper.writeValueAsString(tokens);

        // Then: certificateIds field should be present in JSON
        assertThat(json).contains("\"certificateIds\"");
        assertThat(json).contains("\"cert-id\"");
    }

    @Test
    void roundTripSerialization() throws Exception {
        final UserAndTimestamp creation = UserAndTimestamp.of(Author.SYSTEM);
        final Token token = new Token("app-id-1", "appToken-secret-1", false, null, creation, null, null);
        final CertificateAppIdentity certAppIdentity =
                new CertificateAppIdentity("app-id-2", "cert-id", false, false, creation);
        final ImmutableMap<String, AppIdentity> appIds = ImmutableMap.of("app-id-1", token,
                                                                         "app-id-2", certAppIdentity);
        final ImmutableMap<String, String> secrets = ImmutableMap.of("appToken-secret-1", "app-id-1");
        final ImmutableMap<String, String> certificateIds = ImmutableMap.of("cert-id", "app-id-2");
        final Tokens originalTokens = new Tokens(appIds, secrets, certificateIds);

        // When: Serializing and then deserializing
        final String json = mapper.writeValueAsString(originalTokens);
        final Tokens deserializedTokens = mapper.readValue(json, Tokens.class);

        // Then: All fields should be preserved
        assertThat(deserializedTokens.appIds()).isEqualTo(originalTokens.appIds());
        assertThat(deserializedTokens.secrets()).isEqualTo(originalTokens.secrets());
        assertThat(deserializedTokens.certificateIds()).isEqualTo(originalTokens.certificateIds());
    }

    @Test
    void roundTripSerializationWithoutCertificateIds() throws Exception {
        final UserAndTimestamp creation = UserAndTimestamp.of(Author.SYSTEM);
        final Token token = new Token("app-id-1", "appToken-secret-1", false, null, creation, null, null);
        final ImmutableMap<String, AppIdentity> appIds = ImmutableMap.of("app-id-1", token);
        final ImmutableMap<String, String> secrets = ImmutableMap.of("appToken-secret-1", "app-id-1");
        final Tokens originalTokens = new Tokens(appIds, secrets, null);

        // When: Serializing and then deserializing
        final String json = mapper.writeValueAsString(originalTokens);
        final Tokens deserializedTokens = mapper.readValue(json, Tokens.class);

        // Then: All fields should be preserved (certificateIds as empty)
        assertThat(deserializedTokens.appIds()).isEqualTo(originalTokens.appIds());
        assertThat(deserializedTokens.secrets()).isEqualTo(originalTokens.secrets());
        assertThat(deserializedTokens.certificateIds()).isEmpty();
    }
}
