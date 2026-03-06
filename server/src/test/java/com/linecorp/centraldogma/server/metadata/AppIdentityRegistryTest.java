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

class AppIdentityRegistryTest {

    private static final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void nullCertificateIds() {
        final UserAndTimestamp creation = UserAndTimestamp.of(Author.SYSTEM);
        final Token token = new Token("app-id-1", "appToken-secret-1", false, null, creation, null, null);
        final ImmutableMap<String, AppIdentity> appIds = ImmutableMap.of("app-id-1", token);
        final ImmutableMap<String, String> secrets = ImmutableMap.of("appToken-secret-1", "app-id-1");

        final AppIdentityRegistry appIdentityRegistry = new AppIdentityRegistry(appIds, secrets, null);

        assertThat(appIdentityRegistry.appIds()).isEqualTo(appIds);
        assertThat(appIdentityRegistry.secrets()).isEqualTo(secrets);
        assertThat(appIdentityRegistry.certificateIds()).isNotNull();
        assertThat(appIdentityRegistry.certificateIds()).isEmpty();
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

        final AppIdentityRegistry appIdentityRegistry =
                new AppIdentityRegistry(appIds, secrets, certificateIds);

        assertThat(appIdentityRegistry.appIds()).isEqualTo(appIds);
        assertThat(appIdentityRegistry.secrets()).isEqualTo(secrets);
        assertThat(appIdentityRegistry.certificateIds()).isEqualTo(certificateIds);
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

        final AppIdentityRegistry appIdentityRegistry = mapper.readValue(legacyJson, AppIdentityRegistry.class);

        assertThat(appIdentityRegistry.appIds()).hasSize(1);
        assertThat(appIdentityRegistry.secrets()).hasSize(1);
        assertThat(appIdentityRegistry.certificateIds()).isNotNull();
        assertThat(appIdentityRegistry.certificateIds()).isEmpty();
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
        final AppIdentityRegistry appIdentityRegistry = mapper.readValue(newJson, AppIdentityRegistry.class);

        assertThat(appIdentityRegistry.appIds()).hasSize(2);
        assertThat(appIdentityRegistry.secrets()).hasSize(1);
        assertThat(appIdentityRegistry.certificateIds()).hasSize(1);
        assertThat(appIdentityRegistry.certificateIds().get("cert-id")).isEqualTo("app-id-2");
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
        final AppIdentityRegistry appIdentityRegistry =
                new AppIdentityRegistry(appIds, secrets, certificateIds);

        final String json = mapper.writeValueAsString(appIdentityRegistry);

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
        final AppIdentityRegistry originalAppIdentityRegistry =
                new AppIdentityRegistry(appIds, secrets, certificateIds);

        // When: Serializing and then deserializing
        final String json = mapper.writeValueAsString(originalAppIdentityRegistry);
        final AppIdentityRegistry deserializedAppIdentityRegistry =
                mapper.readValue(json, AppIdentityRegistry.class);

        // Then: All fields should be preserved
        assertThat(deserializedAppIdentityRegistry.appIds()).isEqualTo(originalAppIdentityRegistry.appIds());
        assertThat(deserializedAppIdentityRegistry.secrets()).isEqualTo(originalAppIdentityRegistry.secrets());
        assertThat(deserializedAppIdentityRegistry.certificateIds())
                .isEqualTo(originalAppIdentityRegistry.certificateIds());
    }

    @Test
    void roundTripSerializationWithoutCertificateIds() throws Exception {
        final UserAndTimestamp creation = UserAndTimestamp.of(Author.SYSTEM);
        final Token token = new Token("app-id-1", "appToken-secret-1", false, null, creation, null, null);
        final ImmutableMap<String, AppIdentity> appIds = ImmutableMap.of("app-id-1", token);
        final ImmutableMap<String, String> secrets = ImmutableMap.of("appToken-secret-1", "app-id-1");
        final AppIdentityRegistry originalAppIdentityRegistry = new AppIdentityRegistry(appIds, secrets, null);

        // When: Serializing and then deserializing
        final String json = mapper.writeValueAsString(originalAppIdentityRegistry);
        final AppIdentityRegistry deserializedAppIdentityRegistry =
                mapper.readValue(json, AppIdentityRegistry.class);

        // Then: All fields should be preserved (certificateIds as empty)
        assertThat(deserializedAppIdentityRegistry.appIds()).isEqualTo(originalAppIdentityRegistry.appIds());
        assertThat(deserializedAppIdentityRegistry.secrets()).isEqualTo(originalAppIdentityRegistry.secrets());
        assertThat(deserializedAppIdentityRegistry.certificateIds()).isEmpty();
    }
}
