/*
 * Copyright 2022 LINE Corporation
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

import static net.javacrumbs.jsonunit.core.Option.IGNORING_ARRAY_ORDER;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class RequestLogTest {
    private static String LOGGER_NAME = "com.linecorp.centraldogma.test";


    private ListAppender<ILoggingEvent> logWatcher;

    private void setUp() {
        logWatcher = new ListAppender<>();
        logWatcher.start();
        final Logger logger = (Logger) LoggerFactory.getLogger(LOGGER_NAME);
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logWatcher);
    }

    @Test
    void jsonSerialization() throws Exception {
        final String json = "{\n" +
                            "  \"targetGroups\": [ \"API\", \"HEALTH\" ],\n" +
                            "  \"loggerName\": \"centraldogma.test\",\n" +
                            "  \"requestLogLevel\": \"DEBUG\",\n" +
                            "  \"successfulResponseLogLevel\": \"DEBUG\",\n" +
                            "  \"failureResponseLogLevel\": \"ERROR\",\n" +
                            "  \"successSamplingRate\": 0.4,\n" +
                            "  \"failureSamplingRate\": 0.6\n" +
                            '}';
        final RequestLogConfig requestLogConfig = Jackson.readValue(json, RequestLogConfig.class);
        assertThat(requestLogConfig.targetGroups())
                .containsExactlyInAnyOrder(RequestLogGroup.API, RequestLogGroup.HEALTH);
        assertThat(requestLogConfig.loggerName()).isEqualTo("centraldogma.test");
        assertThat(requestLogConfig.requestLogLevel()).isEqualTo(LogLevel.DEBUG);
        assertThat(requestLogConfig.successfulResponseLogLevel()).isEqualTo(LogLevel.DEBUG);
        assertThat(requestLogConfig.failureResponseLogLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(requestLogConfig.successSamplingRate()).isEqualTo(0.4f);
        assertThat(requestLogConfig.failureSamplingRate()).isEqualTo(0.6f);
        assertThatJson(Jackson.writeValueAsString(requestLogConfig))
                .when(IGNORING_ARRAY_ORDER)
                .isEqualTo(json);
    }

    @CsvSource({ "API, /api/v1/projects", "HEALTH, /monitor/l7check", "METRICS, /monitor/metrics",
                 "DOCS, /docs/index.html", "WEB, /styles/main.css" })
    @ParameterizedTest
    void shouldLogRequests(RequestLogGroup logGroup, String path) throws Exception {
        final CentralDogmaExtension dogmaExtension = newDogmaExtension(logGroup);
        final BlockingWebClient client = dogmaExtension.httpClient().blocking();
        setUp();
        assertThat(client.get(path).status()).isEqualTo(HttpStatus.OK);

        await().untilAsserted(() -> {
            assertThat(logWatcher.list)
                    .anyMatch(event -> {
                        return event.getLevel().equals(Level.DEBUG) &&
                               event.getMessage().contains("{} Request: {}") &&
                               event.getFormattedMessage().contains(path);
                    });
        });
        dogmaExtension.stop();
    }

    @CsvSource({ "/api/v1/projects", "/monitor/l7check", "/monitor/metrics",
                 "/docs/index.html", "/styles/main.css" })
    @ParameterizedTest
    void shouldAllLogRequests(String path) throws Exception {
        final CentralDogmaExtension dogmaExtension = newDogmaExtension(RequestLogGroup.ALL);
        final BlockingWebClient client = dogmaExtension.httpClient().blocking();
        setUp();
        assertThat(client.get(path).status()).isEqualTo(HttpStatus.OK);

        await().untilAsserted(() -> {
            assertThat(logWatcher.list)
                    .anyMatch(event -> {
                        return event.getLevel().equals(Level.DEBUG) &&
                               event.getMessage().contains("{} Request: {}") &&
                               event.getFormattedMessage().contains(path);
                    });
        });
        dogmaExtension.stop();
    }

    @Test
    void noSamplingSuccess() throws Exception {
        final CentralDogmaExtension dogmaExtension = newDogmaExtension(0, 1.0f, RequestLogGroup.ALL);
        final BlockingWebClient client = dogmaExtension.httpClient().blocking();
        setUp();
        assertThat(client.get("/api/v1/projects").status()).isEqualTo(HttpStatus.OK);

        Thread.sleep(2000);
        assertThat(logWatcher.list)
                .noneMatch(event -> {
                    return event.getLevel().equals(Level.DEBUG) &&
                           event.getMessage().contains("{} Request: {}") &&
                           event.getFormattedMessage().contains("/api/v1/projects");

                });
        dogmaExtension.stop();
    }

    @Test
    void noSamplingFailure() throws Exception {
        final CentralDogmaExtension dogmaExtension = newDogmaExtension(1.0f, 0, RequestLogGroup.ALL);
        final BlockingWebClient unauthorizedClient = WebClient.of(dogmaExtension.httpClient().uri()).blocking();
        setUp();
        assertThat(unauthorizedClient.get("/api/v1/projects").status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Thread.sleep(2000);
        assertThat(logWatcher.list)
                .noneMatch(event -> {
                    return event.getLevel().equals(Level.DEBUG) &&
                           event.getMessage().contains("{} Request: {}") &&
                           event.getFormattedMessage().contains("/api/v1/projects");

                });
        dogmaExtension.stop();
    }

    @CsvSource({ "API, /api/v1/projects", "HEALTH, /monitor/l7check", "METRICS, /monitor/metrics",
                 "DOCS, /docs/index.html", "WEB, /styles/main.css" })
    @ParameterizedTest
    void shouldNotLogApiRequests(RequestLogGroup logGroup, String path) throws Exception {
        final RequestLogGroup[] logGroups =
                Arrays.stream(RequestLogGroup.values())
                      .filter(group -> group != RequestLogGroup.ALL && group != logGroup)
                      .toArray(RequestLogGroup[]::new);
        final CentralDogmaExtension dogmaExtension = newDogmaExtension(logGroups);
        final BlockingWebClient client = dogmaExtension.httpClient().blocking();
        setUp();

        assertThat(client.get(path).status()).isEqualTo(HttpStatus.OK);
        Thread.sleep(2000);
        assertThat(logWatcher.list)
                .noneMatch(event -> {
                    return event.getLevel().equals(Level.DEBUG) &&
                           event.getMessage().contains("{} Request: {}") &&
                           event.getFormattedMessage().contains("/api/v1/projects");

                });
        dogmaExtension.stop();
    }

    private CentralDogmaExtension newDogmaExtension(RequestLogGroup... logGroup) throws Exception {
        return newDogmaExtension(1.0f, 1.0f, logGroup);
    }

    private CentralDogmaExtension newDogmaExtension(float successSamplingRate, float failureSamplingRate,
                                                    RequestLogGroup... logGroup) throws Exception {
        final CentralDogmaExtension dogmaExtension = new CentralDogmaExtension() {
            @Override
            protected void configure(CentralDogmaBuilder builder) {
                builder.webAppEnabled(true);
                final RequestLogConfig requestLogConfig =
                        new RequestLogConfig(ImmutableSet.copyOf(logGroup),
                                             LOGGER_NAME,
                                             LogLevel.DEBUG,
                                             LogLevel.INFO,
                                             LogLevel.ERROR,
                                             successSamplingRate,
                                             failureSamplingRate);
                builder.requestLogConfig(requestLogConfig);
            }

            @Override
            protected void configureHttpClient(WebClientBuilder builder) {
                builder.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
            }

            @Override
            protected void scaffold(CentralDogma client) {
                client.createProject("foo").join();
            }
        };
        dogmaExtension.start();
        dogmaExtension.before(null);
        setUp();
        return dogmaExtension;
    }
}
