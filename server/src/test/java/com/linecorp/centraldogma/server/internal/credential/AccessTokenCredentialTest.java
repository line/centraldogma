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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.credential.Credential;

class AccessTokenCredentialTest {

    @Test
    void testConstruction() throws Exception {
        // null checks
        assertThatThrownBy(() -> new AccessTokenCredential("foo", "projects/foo", true, null))
                .isInstanceOf(NullPointerException.class);

        // emptiness checks
        assertThatThrownBy(() -> new AccessTokenCredential("foo", "projects/foo", true, ""))
                .isInstanceOf(IllegalArgumentException.class);

        // successful construction
        final AccessTokenCredential c = new AccessTokenCredential("foo", "projects/foo", true, "sesame");
        assertThat(c.id()).isEqualTo("foo");
        assertThat(c.accessToken()).isEqualTo("sesame");
    }

    @Test
    void testDeserialization() throws Exception {
        assertThat(Jackson.readValue('{' +
                                     "  \"type\": \"access_token\"," +
                                     "  \"resourceName\": \"projects/foo\"," +
                                     "  \"id\": \"foo\"," +
                                     "  \"accessToken\": \"sesame\"" +
                                     '}', Credential.class))
                .isEqualTo(new AccessTokenCredential("foo", "projects/foo", true, "sesame"));
    }
}
