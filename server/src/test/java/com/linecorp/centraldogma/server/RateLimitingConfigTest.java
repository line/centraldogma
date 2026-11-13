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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.centraldogma.internal.Jackson;

class RateLimitingConfigTest {

    @Test
    void fullStringMatching() {
        final RepositoryRateLimitConfig config =
                new RepositoryRateLimitConfig("payments/config", 30L, TimeUnit.MINUTES);
        assertThat(config.matches("payments/config")).isTrue();
        assertThat(config.matches("payments/configExtra")).isFalse();
        assertThat(config.matches("otherpayments/config")).isFalse();

        final RepositoryRateLimitConfig project =
                new RepositoryRateLimitConfig("payments/.*", 200L, TimeUnit.HOURS);
        assertThat(project.matches("payments/config")).isTrue();
        assertThat(project.matches("payments/other")).isTrue();
        assertThat(project.matches("other/config")).isFalse();
        assertThat(new RepositoryRateLimitConfig(".*", 100L, TimeUnit.DAYS).matches("@internal/repo"))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "", ",\"rateLimiting\":null", ",\"rateLimiting\":{}",
            ",\"rateLimiting\":{\"global\":null,\"repositories\":[]}" })
    void disabledByDefault(String rateLimiting) throws Exception {
        final RateLimitingConfig config = loadConfiguration(rateLimiting).rateLimiting();
        assertThat(config.global()).isNull();
        assertThat(config.repositories()).isEmpty();
    }

    @Test
    void loadServerConfiguration() throws Exception {
        final RateLimitingConfig config = loadConfiguration(
                ",\"rateLimiting\":{\"global\":{\"requests\":1000,\"timeUnit\":\"DAYS\"}," +
                "\"repositories\":[" +
                "{\"pattern\":\"payments/config\",\"requests\":30,\"timeUnit\":\"MINUTES\"}," +
                "{\"pattern\":\"payments/unlimited\",\"requests\":-1,\"timeUnit\":\"SECONDS\"}," +
                "{\"pattern\":\"payments/.*\",\"requests\":200,\"timeUnit\":\"HOURS\"}," +
                "{\"pattern\":\".*\",\"requests\":100,\"timeUnit\":\"DAYS\"}]}").rateLimiting();
        assertThat(config.global().requests()).isEqualTo(1000);
        assertThat(config.global().timeUnit()).isEqualTo(TimeUnit.DAYS);
        assertThat(config.repositories()).extracting(RepositoryRateLimitConfig::pattern)
                                         .containsExactly("payments/config", "payments/unlimited",
                                                          "payments/.*", ".*");
        assertThat(config.repositories()).extracting(RepositoryRateLimitConfig::requests)
                                         .containsExactly(30L, -1L, 200L, 100L);
        assertThat(config.repositories()).extracting(RepositoryRateLimitConfig::timeUnit)
                                         .containsExactly(TimeUnit.MINUTES, TimeUnit.SECONDS,
                                                          TimeUnit.HOURS, TimeUnit.DAYS);
        final RateLimitingConfig roundTrip = loadConfiguration(
                ",\"rateLimiting\":" + Jackson.writeValueAsString(config)).rateLimiting();
        final JsonNode roundTripJson = Jackson.valueToTree(roundTrip);
        assertThat(roundTripJson).isEqualTo(Jackson.valueToTree(config));
    }

    @ParameterizedTest
    @EnumSource(TimeUnit.class)
    void configurableTimeUnit(TimeUnit timeUnit) throws Exception {
        final RateLimitingConfig config = loadConfiguration(
                ",\"rateLimiting\":{\"global\":{\"requests\":1,\"timeUnit\":\"" + timeUnit + "\"}," +
                "\"repositories\":[{\"pattern\":\".*\",\"requests\":2,\"timeUnit\":\"" + timeUnit + "\"}]}")
                .rateLimiting();
        assertThat(config.global().timeUnit()).isEqualTo(timeUnit);
        assertThat(config.repositories().get(0).timeUnit()).isEqualTo(timeUnit);
    }

    @Test
    void globalAndRepositoryLimitsAreOptional() throws Exception {
        assertThat(loadConfiguration(
                ",\"rateLimiting\":{\"global\":{\"requests\":10,\"timeUnit\":\"HOURS\"}}")
                .rateLimiting().repositories()).isEmpty();
        assertThat(loadConfiguration(
                ",\"rateLimiting\":{\"repositories\":[" +
                "{\"pattern\":\".*\",\"requests\":10,\"timeUnit\":\"MINUTES\"}]}")
                .rateLimiting().global()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = { -1, 0, 1 })
    void unlimitedRejectedAndLimitedCounts(long requests) throws Exception {
        final RateLimitingConfig config = loadConfiguration(
                ",\"rateLimiting\":{\"global\":{\"requests\":" + requests + ",\"timeUnit\":\"MINUTES\"}," +
                "\"repositories\":[{\"pattern\":\".*\",\"requests\":" + requests +
                ",\"timeUnit\":\"MINUTES\"}]}").rateLimiting();
        assertThat(config.global().requests()).isEqualTo(requests);
        assertThat(config.repositories().get(0).requests()).isEqualTo(requests);
    }

    @Test
    void rejectCountsBelowUnlimited() {
        assertThatThrownBy(() -> new RateLimitConfig(-2L, TimeUnit.DAYS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RepositoryRateLimitConfig(".*", -2L, TimeUnit.HOURS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "[", "*" })
    void rejectInvalidPatterns(String pattern) {
        assertThatThrownBy(() -> new RepositoryRateLimitConfig(pattern, 1L, TimeUnit.MINUTES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"requests\":1}", "{\"timeUnit\":\"DAYS\"}",
            "{\"requests\":1,\"timeUnit\":null}",
            "{\"requests\":-2,\"timeUnit\":\"MINUTES\"}",
            "{\"requests\":1,\"timeUnit\":\"WEEKS\"}",
            "{\"requests\":9223372036854775808,\"timeUnit\":\"DAYS\"}"
    })
    void rejectInvalidLimits(String json) throws Exception {
        assertThatThrownBy(() -> loadConfiguration(
                ",\"rateLimiting\":{\"global\":" + json + '}')).isInstanceOf(JsonMappingException.class);
        final ObjectNode repositoryRule = (ObjectNode) Jackson.readTree(json);
        repositoryRule.put("pattern", ".*");
        assertThatThrownBy(() -> loadConfiguration(
                ",\"rateLimiting\":{\"repositories\":[" + repositoryRule + "]}"))
                .isInstanceOf(JsonMappingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"requests\":1,\"timeUnit\":\"DAYS\"}",
            "{\"pattern\":null,\"requests\":1,\"timeUnit\":\"DAYS\"}",
            "{\"pattern\":\"[\",\"requests\":1,\"timeUnit\":\"DAYS\"}" })
    void rejectInvalidRepositoryRules(String json) {
        assertThatThrownBy(() -> loadConfiguration(
                ",\"rateLimiting\":{\"repositories\":[" + json + "]}"))
                .isInstanceOf(JsonMappingException.class);
    }

    private static CentralDogmaConfig loadConfiguration(String rateLimiting) throws Exception {
        return CentralDogmaConfig.load(
                "{\"dataDir\":\"./data\",\"ports\":[{" +
                "\"localAddress\":{\"host\":\"*\",\"port\":0},\"protocols\":[\"http\"]}]" +
                rateLimiting + '}');
    }
}
