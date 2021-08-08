/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.mirror.credential;

import static com.linecorp.centraldogma.server.internal.mirror.credential.MirrorCredentialTest.HOSTNAME_PATTERNS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.internal.jackson.Jackson;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;

class PasswordMirrorCredentialTest {

    @Test
    void testConstruction() throws Exception {
        // null checks
        assertThatThrownBy(() -> new PasswordMirrorCredential(null, null, null, "sesame"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PasswordMirrorCredential(null, null, "trustin", null))
                .isInstanceOf(NullPointerException.class);

        // emptiness checks
        assertThatThrownBy(() -> new PasswordMirrorCredential(null, null, "", "sesame"))
                .isInstanceOf(IllegalArgumentException.class);

        // An empty password must be allowed because some servers uses password authentication
        // as token-based authentication whose username is the token and password is an empty string.
        assertThat(new PasswordMirrorCredential(null, null, "trustin", "").password()).isEmpty();

        // successful construction
        final PasswordMirrorCredential c = new PasswordMirrorCredential(null, null, "trustin", "sesame");
        assertThat(c.username()).isEqualTo("trustin");
        assertThat(c.password()).isEqualTo("sesame");
    }

    @Test
    void testDeserialization() throws Exception {
        // With hostnamePatterns
        assertThat(Jackson.ofJson().readValue('{' +
                                              "  \"type\": \"password\"," +
                                              "  \"hostnamePatterns\": [" +
                                              "    \"^foo\\\\.com$\"" +
                                              "  ]," +
                                              "  \"username\": \"trustin\"," +
                                              "  \"password\": \"sesame\"" +
                                              '}', MirrorCredential.class))
                .isEqualTo(new PasswordMirrorCredential(null, HOSTNAME_PATTERNS,
                                                        "trustin", "sesame"));
        // With ID
        assertThat(Jackson.ofJson().readValue('{' +
                                              "  \"type\": \"password\"," +
                                              "  \"id\": \"foo\"," +
                                              "  \"username\": \"trustin\"," +
                                              "  \"password\": \"sesame\"" +
                                              '}', MirrorCredential.class))
                .isEqualTo(new PasswordMirrorCredential("foo", null, "trustin", "sesame"));
    }
}
