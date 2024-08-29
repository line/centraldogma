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

package com.linecorp.centraldogma.server.internal.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.credential.Credential;

class PasswordCredentialTest {

    @Test
    void testConstruction() throws Exception {
        // null checks
        assertThatThrownBy(() -> new PasswordCredential("foo", true, null, "sesame"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PasswordCredential("foo", true, "trustin", null))
                .isInstanceOf(NullPointerException.class);

        // emptiness checks
        assertThatThrownBy(() -> new PasswordCredential("foo", true, "", "sesame"))
                .isInstanceOf(IllegalArgumentException.class);

        // An empty password must be allowed because some servers uses password authentication
        // as token-based authentication whose username is the token and password is an empty string.
        assertThat(new PasswordCredential("foo", true, "trustin", "").password()).isEmpty();

        // successful construction
        final PasswordCredential c = new PasswordCredential("foo", true, "trustin", "sesame");
        assertThat(c.id()).isEqualTo("foo");
        assertThat(c.username()).isEqualTo("trustin");
        assertThat(c.password()).isEqualTo("sesame");
    }

    @Test
    void testDeserialization() throws Exception {
        assertThat(Jackson.readValue('{' +
                                     "  \"type\": \"password\"," +
                                     "  \"id\": \"foo\"," +
                                     "  \"username\": \"trustin\"," +
                                     "  \"password\": \"sesame\"" +
                                     '}', Credential.class))
                .isEqualTo(new PasswordCredential("foo", true, "trustin", "sesame"));
    }
}
