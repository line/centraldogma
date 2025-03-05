/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.credential;

import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.credential.CredentialType;
import com.linecorp.centraldogma.server.credential.LegacyCredential;

class AccessTokenCredentialTest {

    @Test
    void testConstruction() throws Exception {
        final String credentialName = credentialName("foo", "foo-credential");
        // null checks
        assertThatThrownBy(() -> new AccessTokenCredential(credentialName, null))
                .isInstanceOf(NullPointerException.class);

        // emptiness checks
        assertThatThrownBy(() -> new AccessTokenCredential(credentialName, ""))
                .isInstanceOf(IllegalArgumentException.class);

        // successful construction
        final AccessTokenCredential c = new AccessTokenCredential(credentialName, "sesame");
        assertThat(c.name()).isEqualTo(credentialName);
        assertThat(c.id()).isEqualTo("foo-credential");
        assertThat(c.type()).isSameAs(CredentialType.ACCESS_TOKEN);
        assertThat(c.accessToken()).isEqualTo("sesame");
    }

    @Test
    void testDeserialization() throws Exception {
        // Legacy format
        assertThat(Jackson.readValue('{' +
                                     "  \"type\": \"access_token\"," +
                                     "  \"id\": \"foo\"," +
                                     "  \"accessToken\": \"sesame\"" +
                                     '}', LegacyCredential.class))
                .isEqualTo(new AccessTokenLegacyCredential("foo", true, "sesame"));

        // New format
        final String credentialName = credentialName("foo", "foo-credential");
        assertThat(Jackson.readValue('{' +
                                     "  \"type\": \"ACCESS_TOKEN\"," +
                                     "  \"name\": \"" + credentialName + "\"," +
                                     "  \"accessToken\": \"sesame\"" +
                                     '}', Credential.class))
                .isEqualTo(new AccessTokenCredential(credentialName, "sesame"));
    }
}
