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

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.internal.Jackson;

class RateLimitingConfigTest {

    @Test
    void fullStringMatching() {
        final RateLimitingConfig config = new RateLimitingConfig("payments/config", 0.5);
        assertThat(config.matches("payments/config")).isTrue();
        assertThat(config.matches("payments/configExtra")).isFalse();
        assertThat(config.matches("otherpayments/config")).isFalse();

        final RateLimitingConfig project = new RateLimitingConfig("payments/.*", 200.0);
        assertThat(project.matches("payments/config")).isTrue();
        assertThat(project.matches("payments/other")).isTrue();
        assertThat(project.matches("other/config")).isFalse();
        assertThat(new RateLimitingConfig(".*", 100.0).matches("@internal/repo")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "", ",\"rateLimiting\":null", ",\"rateLimiting\":[]" })
    void disabledByDefault(String rateLimiting) throws Exception {
        assertThat(loadConfiguration(rateLimiting).rateLimiting()).isEmpty();
    }

    @Test
    void loadServerConfiguration() throws Exception {
        final CentralDogmaConfig config = loadConfiguration(
                ",\"rateLimiting\":[" +
                "{\"pattern\":\"payments/config\",\"requestsPerSecond\":0.5}," +
                "{\"pattern\":\"payments/unlimited\",\"requestsPerSecond\":0}," +
                "{\"pattern\":\"payments/.*\",\"requestsPerSecond\":200}," +
                "{\"pattern\":\".*\",\"requestsPerSecond\":100}]");
        assertThat(config.rateLimiting()).extracting(RateLimitingConfig::pattern)
                                        .containsExactly("payments/config", "payments/unlimited",
                                                         "payments/.*", ".*");
        assertThat(config.rateLimiting()).extracting(RateLimitingConfig::requestsPerSecond)
                                        .containsExactly(0.5, 0.0, 200.0, 100.0);
        final CentralDogmaConfig roundTrip = loadConfiguration(
                ",\"rateLimiting\":" + Jackson.writeValueAsString(config.rateLimiting()));
        final JsonNode roundTripJson = Jackson.valueToTree(roundTrip.rateLimiting());
        assertThat(roundTripJson).isEqualTo(Jackson.valueToTree(config.rateLimiting()));
    }

    @ParameterizedTest
    @ValueSource(doubles = { -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY })
    void rejectInvalidRates(double rate) {
        assertThatThrownBy(() -> new RateLimitingConfig(".*", rate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "[", "*" })
    void rejectInvalidPatterns(String pattern) {
        assertThatThrownBy(() -> new RateLimitingConfig(pattern, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"pattern\":\".*\"}", "{\"requestsPerSecond\":1}",
            "{\"pattern\":null,\"requestsPerSecond\":1}",
            "{\"pattern\":\".*\",\"requestsPerSecond\":null}",
            "{\"pattern\":\"[\",\"requestsPerSecond\":1}"
    })
    void rejectInvalidJson(String json) {
        assertThatThrownBy(() -> loadConfiguration(
                ",\"rateLimiting\":[" + json + ']')).isInstanceOf(JsonMappingException.class);
    }

    private static CentralDogmaConfig loadConfiguration(String rateLimiting) throws Exception {
        return CentralDogmaConfig.load(
                "{\"dataDir\":\"./data\",\"ports\":[{" +
                "\"localAddress\":{\"host\":\"*\",\"port\":0},\"protocols\":[\"http\"]}]" +
                rateLimiting + '}');
    }
}
