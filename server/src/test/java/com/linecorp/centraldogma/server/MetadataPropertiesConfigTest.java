/*
 * Copyright 2026 LINE Corporation
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

import org.junit.jupiter.api.Test;

import com.linecorp.centraldogma.internal.Jackson;

class MetadataPropertiesConfigTest {

    @Test
    void parsesMetadataProperties() throws Exception {
        final CentralDogmaConfig cfg =
                Jackson.readValue("{\n" +
                                  "  \"dataDir\": \"./data\",\n" +
                                  "  \"ports\": [\n" +
                                  "    {\n" +
                                  "      \"localAddress\": {\n" +
                                  "        \"host\": \"*\",\n" +
                                  "        \"port\": 36462\n" +
                                  "      },\n" +
                                  "      \"protocols\": [ \"http\" ]\n" +
                                  "    }\n" +
                                  "  ],\n" +
                                  "  \"metadataProperties\": {\n" +
                                  "    \"project\": {\n" +
                                  "      \"type\": \"object\",\n" +
                                  "      \"properties\": { \"serviceId\": { \"type\": \"string\" } },\n" +
                                  "      \"required\": [ \"serviceId\" ]\n" +
                                  "    },\n" +
                                  "    \"repo\": { \"type\": \"object\" }\n" +
                                  "  }\n" +
                                  '}',
                                  CentralDogmaConfig.class);
        final MetadataPropertiesConfig metadataProperties = cfg.metadataProperties();
        assertThat(metadataProperties).isNotNull();
        assertThat(metadataProperties.project().get("required").get(0).asText()).isEqualTo("serviceId");
        assertThat(metadataProperties.repo()).isEqualTo(Jackson.readTree("{ \"type\": \"object\" }"));
        assertThat(metadataProperties.appIdentity()).isNull();
    }

    @Test
    void nullWhenAbsent() throws Exception {
        final CentralDogmaConfig cfg =
                Jackson.readValue("{\n" +
                                  "  \"dataDir\": \"./data\",\n" +
                                  "  \"ports\": [\n" +
                                  "    {\n" +
                                  "      \"localAddress\": { \"host\": \"*\", \"port\": 36462 },\n" +
                                  "      \"protocols\": [ \"http\" ]\n" +
                                  "    }\n" +
                                  "  ]\n" +
                                  '}',
                                  CentralDogmaConfig.class);
        assertThat(cfg.metadataProperties()).isNull();
    }
}
