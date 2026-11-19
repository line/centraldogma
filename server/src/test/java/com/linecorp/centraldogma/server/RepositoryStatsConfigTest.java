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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.centraldogma.internal.Jackson;

class RepositoryStatsConfigTest {

    private static final String BASE_CONFIG =
            "{\"dataDir\":\"./data\",\"ports\":[{\"localAddress\":{\"host\":\"*\",\"port\":36462}," +
            "\"protocols\":[\"http\"]}]}";

    @TempDir
    File dataDir;

    @Test
    void disabledByDefault() throws Exception {
        assertThat(Jackson.readValue(BASE_CONFIG, CentralDogmaConfig.class).repositoryStatsWindowHours())
                .isZero();
        try (CentralDogma dogma = new CentralDogmaBuilder(dataDir).build()) {
            assertThat(dogma.config().repositoryStatsWindowHours()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 24, 72 })
    void configuredWindowSurvivesSerialization(int windowHours) throws Exception {
        try (CentralDogma dogma = new CentralDogmaBuilder(dataDir)
                .repositoryStatsWindowHours(windowHours).build()) {
            final String json = Jackson.writeValueAsString(dogma.config());
            assertThat(Jackson.readTree(json).get("repositoryStatsWindowHours").asInt()).isEqualTo(windowHours);
            assertThat(Jackson.readValue(json, CentralDogmaConfig.class).repositoryStatsWindowHours())
                    .isEqualTo(windowHours);
        }
    }

    @Test
    void rejectsNegativeWindow() throws Exception {
        assertThatThrownBy(() -> new CentralDogmaBuilder(dataDir).repositoryStatsWindowHours(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repositoryStatsWindowHours");
        final ObjectNode json = (ObjectNode) Jackson.readTree(BASE_CONFIG);
        json.put("repositoryStatsWindowHours", -1);
        assertThatThrownBy(() -> Jackson.readValue(json.toString(), CentralDogmaConfig.class))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repositoryStatsWindowHours");
    }
}
