/*
 * Copyright 2024 LINE Corporation
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

import com.linecorp.centraldogma.internal.Jackson;

class TokenTest {

    private static final String APP_ID = "foo-id";
    private static final String APP_SECRET = "appToken-foo";

    @Test
    void deserializeToken() throws Exception {
        final String legacyTokenJson = tokenJson(true);
        final Token legacyToken = Jackson.readValue(legacyTokenJson, Token.class);
        assertThat(legacyToken.appId()).isEqualTo(APP_ID);
        assertThat(legacyToken.isAdmin()).isTrue();

        final String tokenJson = tokenJson(false);
        final Token token = Jackson.readValue(tokenJson, Token.class);
        assertThat(token.appId()).isEqualTo(APP_ID);
        assertThat(token.isAdmin()).isTrue();
    }

    private static String tokenJson(boolean legacy) {
        return "{\"appId\": \"" + APP_ID + "\"," +
               "  \"secret\": \"" + APP_SECRET + "\"," +
               (legacy ? "  \"admin\": true," : " \"systemAdmin\": true,") +
               "  \"creation\": {" +
               "    \"user\": \"foo@foo.com\"," +
               "    \"timestamp\": \"2018-04-10T09:58:20.032Z\"" +
               "}}";
    }
}
