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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.internal.Jackson;

class AppIdentityDeserializerTest {

    @Test
    void deserializeLegacyTokenWithoutType() throws Exception {
        final String legacyTokenJson = '{' +
                                       "  \"appId\": \"legacy-app\"," +
                                       "  \"secret\": \"legacy-secret\"," +
                                       "  \"systemAdmin\": true," +
                                       "  \"allowGuestAccess\": true," +
                                       "  \"creation\": {" +
                                       "    \"user\": \"admin@localhost.com\"," +
                                       "    \"timestamp\": \"2025-01-01T00:00:00Z\"" +
                                       "  }" +
                                       '}';

        final AppIdentity appIdentity = Jackson.readValue(legacyTokenJson, AppIdentity.class);

        assertThat(appIdentity).isInstanceOf(Token.class);
        final Token token = (Token) appIdentity;
        assertThat(token.appId()).isEqualTo("legacy-app");
        assertThat(token.secret()).isEqualTo("legacy-secret");
        assertThat(token.type()).isEqualTo(AppIdentityType.TOKEN);
        assertThat(token.isSystemAdmin()).isTrue();
        assertThat(token.allowGuestAccess()).isTrue();

        // Serializing the deserialized token should include the "type" field
        final String serialized = Jackson.writeValueAsString(appIdentity);
        assertThat(serialized).contains("\"type\":\"TOKEN\"");
    }

    @Test
    void deserializeTokenWithType() throws Exception {
        final String tokenJson = '{' +
                                 "  \"type\": \"TOKEN\"," +
                                 "  \"appId\": \"app-with-type\"," +
                                 "  \"secret\": \"token-secret\"," +
                                 "  \"systemAdmin\": false," +
                                 "  \"allowGuestAccess\": false," +
                                 "  \"creation\": {" +
                                 "    \"user\": \"user@localhost.com\"," +
                                 "    \"timestamp\": \"2025-01-01T00:00:00Z\"" +
                                 "  }" +
                                 '}';

        final AppIdentity appIdentity = Jackson.readValue(tokenJson, AppIdentity.class);

        assertThat(appIdentity).isInstanceOf(Token.class);
        final Token token = (Token) appIdentity;
        assertThat(token.appId()).isEqualTo("app-with-type");
        assertThat(token.secret()).isEqualTo("token-secret");
        assertThat(token.type()).isEqualTo(AppIdentityType.TOKEN);
        assertThat(token.isSystemAdmin()).isFalse();
        assertThat(token.allowGuestAccess()).isFalse();
    }

    @Test
    void deserializeCertificateAppIdentity() throws Exception {
        final String certJson = '{' +
                                "  \"type\": \"CERTIFICATE\"," +
                                "  \"appId\": \"cert-app\"," +
                                "  \"certificateId\": \"cert-123\"," +
                                "  \"systemAdmin\": false," +
                                "  \"allowGuestAccess\": false," +
                                "  \"creation\": {" +
                                "    \"user\": \"user@localhost.com\"," +
                                "    \"timestamp\": \"2025-01-01T00:00:00Z\"" +
                                "  }" +
                                '}';

        final AppIdentity appIdentity = Jackson.readValue(certJson, AppIdentity.class);

        assertThat(appIdentity).isInstanceOf(CertificateAppIdentity.class);
        final CertificateAppIdentity certAppId = (CertificateAppIdentity) appIdentity;
        assertThat(certAppId.appId()).isEqualTo("cert-app");
        assertThat(certAppId.certificateId()).isEqualTo("cert-123");
        assertThat(certAppId.type()).isEqualTo(AppIdentityType.CERTIFICATE);
        assertThat(certAppId.isSystemAdmin()).isFalse();
        assertThat(certAppId.allowGuestAccess()).isFalse();
    }

    @Test
    void deserializeInvalidJsonWithoutTypeAndSecret() {
        // Invalid JSON without both "type" and "secret" fields
        final String invalidJson = '{' +
                                   "  \"appId\": \"invalid-app\"," +
                                   "  \"systemAdmin\": false," +
                                   "  \"creation\": {" +
                                   "    \"user\": \"user@localhost.com\"," +
                                   "    \"timestamp\": \"2025-01-01T00:00:00Z\"" +
                                   "  }" +
                                   '}';

        assertThatThrownBy(() -> Jackson.readValue(invalidJson, AppIdentity.class))
                .hasMessageContaining(
                        "Cannot deserialize AppIdentity: missing both 'type' and 'secret' fields");
    }
}
