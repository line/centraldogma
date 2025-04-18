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
package com.linecorp.centraldogma.server.auth.saml;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.ConfigValueConverter;
import com.linecorp.centraldogma.server.auth.AuthConfig;

class AuthenticationDeserializationTest {

    @Test
    void authConfigValueConverter() throws Exception {
        final String jsonConfig =
                String.format("{\"authentication\": {" +
                              "   \"factoryClassName\": \"%s\", " +
                              "   \"properties\": {" +
                              "     \"entityId\": \"https://dogma.com\", " +
                              "     \"hostname\": \"dogma.com\", " +
                              "     \"keyStore\": {" +
                              "       \"path\": \"somewhere\", " +
                              "       \"password\": \"encryption:foo\", " +
                              "       \"keyPasswords\": {" +
                              "         \"dogma\": \"encryption:bar\"" +
                              "        }" +
                              "      }," +
                              "     \"idp\": {" +
                              "       \"entityId\": \"dogma-saml\", " +
                              "       \"uri\": \"https://dogma.com/signon\"" +
                              "}}}}", SamlAuthProviderFactory.class.getName());
        final AuthConfig authConfig = Jackson.readValue(jsonConfig, ParentConfig.class).authConfig;
        final SamlAuthProperties properties = authConfig.properties(SamlAuthProperties.class);
        assertThat(properties.keyStore().password()).isEqualTo("foo1");
        assertThat(properties.keyStore().keyPasswords()).containsOnly(Maps.immutableEntry("dogma", "bar1"));
    }

    // This is used by SPI.
    @SuppressWarnings("unused")
    public static class KeyConfigValueConverter implements ConfigValueConverter {

        @Override
        public List<String> supportedPrefixes() {
            return ImmutableList.of("encryption");
        }

        @Override
        public String convert(String prefix, String value) {
            if ("foo".equals(value)) {
                return "foo1";
            }
            if ("bar".equals(value)) {
                return "bar1";
            }
            throw new IllegalArgumentException("unsupported prefix: " + prefix + ", value: " + value);
        }
    }

    static class ParentConfig {
        private final AuthConfig authConfig;

        @JsonCreator
        ParentConfig(@JsonProperty("authentication") AuthConfig authConfig) {
            this.authConfig = authConfig;
        }
    }
}
