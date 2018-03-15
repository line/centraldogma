/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.centraldogma.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.centraldogma.internal.Jackson;

public class ConfigDeserializationTest {

    @ClassRule
    public static TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void tlsConfig() throws Exception {
        final String cert = Jackson.escapeText(folder.newFile().getAbsolutePath());
        final String key = Jackson.escapeText(folder.newFile().getAbsolutePath());

        final String jsonConfig = String.format("{\"tls\": {" +
                                                "\"keyCertChainFile\": \"%s\", " +
                                                "\"keyFile\": \"%s\", " +
                                                "\"keyPassword\": null " +
                                                "}}", cert, key);
        final ParentConfig parentConfig = Jackson.readValue(jsonConfig, ParentConfig.class);
        final TlsConfig tlsConfig = parentConfig.tlsConfig;

        assertThat(tlsConfig.keyCertChainFile()).isNotNull();
        assertThat(tlsConfig.keyCertChainFile().canRead()).isTrue();

        assertThat(tlsConfig.keyFile()).isNotNull();
        assertThat(tlsConfig.keyFile().canRead()).isTrue();

        assertThat(tlsConfig.keyPassword()).isNull();
    }

    static class ParentConfig {
        private final TlsConfig tlsConfig;

        @JsonCreator
        ParentConfig(@JsonProperty("tls") TlsConfig tlsConfig) {
            this.tlsConfig = tlsConfig;
        }
    }
}
