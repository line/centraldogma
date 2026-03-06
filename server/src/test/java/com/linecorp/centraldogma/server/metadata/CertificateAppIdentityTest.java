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

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.internal.Jackson;

class CertificateAppIdentityTest {

    @Test
    void serialize() throws Exception {
        final UserAndTimestamp creation = new UserAndTimestamp(
                "admin@example.com",
                "2025-01-15T10:00:00Z"
        );

        final CertificateAppIdentity certificate = new CertificateAppIdentity(
                "app-123",
                "cert-abc-xyz",
                true,
                false,
                creation,
                null,
                null
        );

        final String json = Jackson.writeValueAsString(certificate);
        final JsonNode node = Jackson.readTree(json);

        assertThat(node.get("appId").asText()).isEqualTo("app-123");
        assertThat(node.get("certificateId").asText()).isEqualTo("cert-abc-xyz");
        assertThat(node.get("type").asText()).isEqualTo("CERTIFICATE");
        assertThat(node.get("systemAdmin").asBoolean()).isTrue();
        assertThat(node.get("allowGuestAccess").asBoolean()).isFalse();
        assertThat(node.get("creation").get("user").asText()).isEqualTo("admin@example.com");
        assertThat(node.get("creation").get("timestamp").asText()).isEqualTo("2025-01-15T10:00:00Z");
        assertThat(node.has("deactivation")).isFalse();
        assertThat(node.has("deletion")).isFalse();
    }

    @Test
    void deserialize() throws Exception {
        final String json = '{' +
                            "  \"appId\": \"app-456\"," +
                            "  \"certificateId\": \"cert-def-123\"," +
                            "  \"type\": \"CERTIFICATE\"," +
                            "  \"systemAdmin\": false," +
                            "  \"allowGuestAccess\": true," +
                            "  \"creation\": {" +
                            "    \"user\": \"user@example.com\"," +
                            "    \"timestamp\": \"2025-02-20T15:30:45Z\"" +
                            "  }" +
                            '}';

        final CertificateAppIdentity certificate = Jackson.readValue(json, CertificateAppIdentity.class);

        assertThat(certificate.appId()).isEqualTo("app-456");
        assertThat(certificate.certificateId()).isEqualTo("cert-def-123");
        assertThat(certificate.type()).isEqualTo(AppIdentityType.CERTIFICATE);
        assertThat(certificate.isSystemAdmin()).isFalse();
        assertThat(certificate.allowGuestAccess()).isTrue();
        assertThat(certificate.creation().user()).isEqualTo("user@example.com");
        assertThat(certificate.creation().timestamp()).isEqualTo("2025-02-20T15:30:45Z");
        assertThat(certificate.deactivation()).isNull();
        assertThat(certificate.deletion()).isNull();
    }

    @Test
    void serializeAndDeserialize() throws Exception {
        final UserAndTimestamp creation = new UserAndTimestamp(
                "admin@example.com",
                "2025-03-25T08:15:30Z"
        );

        final UserAndTimestamp deactivation = new UserAndTimestamp(
                "admin@example.com",
                "2025-04-01T12:00:00Z"
        );

        final CertificateAppIdentity original = new CertificateAppIdentity(
                "app-789",
                "cert-ghi-456",
                true,
                true,
                creation,
                deactivation,
                null
        );

        final String json = Jackson.writeValueAsString(original);
        final CertificateAppIdentity deserialized = Jackson.readValue(json, CertificateAppIdentity.class);

        assertThat(deserialized.appId()).isEqualTo(original.appId());
        assertThat(deserialized.certificateId()).isEqualTo(original.certificateId());
        assertThat(deserialized.type()).isEqualTo(original.type());
        assertThat(deserialized.isSystemAdmin()).isEqualTo(original.isSystemAdmin());
        assertThat(deserialized.allowGuestAccess()).isEqualTo(original.allowGuestAccess());
        assertThat(deserialized.creation().user()).isEqualTo(original.creation().user());
        assertThat(deserialized.creation().timestamp()).isEqualTo(original.creation().timestamp());
        assertThat(deserialized.deactivation())
                .isNotNull()
                .satisfies(deact -> {
                    assertThat(deact.user()).isEqualTo(original.deactivation().user());
                    assertThat(deact.timestamp()).isEqualTo(original.deactivation().timestamp());
                });
        assertThat(deserialized.deletion()).isNull();
    }

    @Test
    void defaultValues() throws Exception {
        // Test that systemAdmin defaults to false and allowGuestAccess defaults to false
        final String json = '{' +
                            "  \"appId\": \"app-default\"," +
                            "  \"certificateId\": \"cert-default\"," +
                            "  \"type\": \"CERTIFICATE\"," +
                            "  \"creation\": {" +
                            "    \"user\": \"user@example.com\"," +
                            "    \"timestamp\": \"2025-01-01T00:00:00Z\"" +
                            "  }" +
                            '}';

        final CertificateAppIdentity certificate = Jackson.readValue(json, CertificateAppIdentity.class);

        assertThat(certificate.isSystemAdmin()).isFalse();
        assertThat(certificate.allowGuestAccess()).isFalse();
    }
}

