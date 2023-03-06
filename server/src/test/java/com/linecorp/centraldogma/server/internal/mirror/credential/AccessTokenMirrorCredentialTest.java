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

package com.linecorp.centraldogma.server.internal.mirror.credential;

import static com.linecorp.centraldogma.server.internal.mirror.credential.MirrorCredentialTest.HOSTNAME_PATTERNS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;

class AccessTokenMirrorCredentialTest {

    @Test
    void testConstruction() throws Exception {
        // null checks
        assertThatThrownBy(() -> new AccessTokenMirrorCredential(null, null, null, true))
                .isInstanceOf(NullPointerException.class);

        // emptiness checks
        assertThatThrownBy(() -> new AccessTokenMirrorCredential(null, null, "", true))
                .isInstanceOf(IllegalArgumentException.class);

        // successful construction
        final AccessTokenMirrorCredential c = new AccessTokenMirrorCredential(null, null, "sesame", true);
        assertThat(c.accessToken()).isEqualTo("sesame");
    }

    @Test
    void testDeserialization() throws Exception {
        // With hostnamePatterns
        assertThat(Jackson.readValue('{' +
                                     "  \"type\": \"access_token\"," +
                                     "  \"hostnamePatterns\": [" +
                                     "    \"^foo\\\\.com$\"" +
                                     "  ]," +
                                     "  \"accessToken\": \"sesame\"" +
                                     '}', MirrorCredential.class))
                .isEqualTo(new AccessTokenMirrorCredential(null, HOSTNAME_PATTERNS,
                                                           "sesame", true));
        // With ID
        assertThat(Jackson.readValue('{' +
                                     "  \"type\": \"access_token\"," +
                                     "  \"id\": \"foo\"," +
                                     "  \"accessToken\": \"sesame\"" +
                                     '}', MirrorCredential.class))
                .isEqualTo(new AccessTokenMirrorCredential("foo", null, "sesame", true));
    }
}
