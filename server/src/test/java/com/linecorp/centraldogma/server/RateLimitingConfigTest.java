/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.RateLimitingConfig.ProjectConfig;

class RateLimitingConfigTest {

    @Test
    void resolveOverrides() {
        final RateLimitingConfig config = new RateLimitingConfig(100.0, ImmutableMap.of(
                "payments", new ProjectConfig(200.0, ImmutableMap.of("config", 20.0, "unlimited", 0.0)),
                "inherited", new ProjectConfig(null, ImmutableMap.of("config", 30.0)),
                "unlimited", new ProjectConfig(0.0, ImmutableMap.of("config", 40.0))));

        assertThat(config.requestsPerSecond("other", "config")).isEqualTo(100);
        assertThat(config.requestsPerSecond("payments", "other")).isEqualTo(200);
        assertThat(config.requestsPerSecond("payments", "config")).isEqualTo(20);
        assertThat(config.requestsPerSecond("payments", "unlimited")).isZero();
        assertThat(config.requestsPerSecond("inherited", "other")).isEqualTo(100);
        assertThat(config.requestsPerSecond("inherited", "config")).isEqualTo(30);
        assertThat(config.requestsPerSecond("unlimited", "other")).isZero();
        assertThat(config.requestsPerSecond("unlimited", "config")).isEqualTo(40);
    }

    @Test
    void disabledByDefault() {
        final RateLimitingConfig config = new RateLimitingConfig(null, null);
        assertThat(config.requestsPerSecond("project", "repo")).isZero();
        assertThat(config.projects()).isEmpty();
    }

    @Test
    void loadServerConfiguration() throws Exception {
        final CentralDogmaConfig config = CentralDogmaConfig.load(
                '{' +
                "\"dataDir\": \"./data\"," +
                "\"ports\": [{\"localAddress\": {\"host\": \"*\", \"port\": 0}," +
                "              \"protocols\": [\"http\"]}]," +
                "\"rateLimiting\": {" +
                "  \"defaultRepositoryRequestsPerSecond\": 100," +
                "  \"projects\": {" +
                "    \"payments\": {" +
                "      \"defaultRepositoryRequestsPerSecond\": 200," +
                "      \"repos\": {\"config\": 20, \"unlimited\": 0}" +
                "    }" +
                "  }" +
                "}}"
        );
        assertThat(config.rateLimiting()).isNotNull();
        assertThat(config.rateLimiting().requestsPerSecond("payments", "config")).isEqualTo(20);
        final RateLimitingConfig roundTrip = Jackson.readValue(
                Jackson.writeValueAsString(config.rateLimiting()), RateLimitingConfig.class);
        final JsonNode roundTripJson = Jackson.valueToTree(roundTrip);
        assertThat(roundTripJson)
                .isEqualTo(Jackson.valueToTree(config.rateLimiting()));
    }

    @ParameterizedTest
    @ValueSource(doubles = { -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY })
    void rejectInvalidRates(double rate) {
        assertThatThrownBy(() -> new RateLimitingConfig(rate, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectConfig(rate, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectConfig(null, ImmutableMap.of("repo", rate)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectInvalidNames() {
        assertThatThrownBy(() -> new RateLimitingConfig(null, ImmutableMap.of(
                "project/repo", new ProjectConfig(null, null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectConfig(null, ImmutableMap.of("repo/path", 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
