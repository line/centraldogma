/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.api;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class LoggerServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @Test
    void logLevel() {
        assertThat(dogma.blockingHttpClient().get("/api/v1/loggers/invalid.package").status())
                .isSameAs(HttpStatus.NOT_FOUND);
        assertThatJson(dogma.blockingHttpClient().get("/api/v1/loggers/com.linecorp").contentUtf8())
                .isEqualTo("{\"name\":\"com.linecorp\",\"level\":\"DEBUG\",\"effectiveLevel\":\"DEBUG\"}");

        assertThatJson(dogma.blockingHttpClient().get(
                "/api/v1/loggers/com.linecorp.armeria.logging.traffic.server.http2").contentUtf8())
                .isEqualTo("{\"name\":\"com.linecorp.armeria.logging.traffic.server.http2\"," +
                           " \"level\":null," +
                           " \"effectiveLevel\":\"DEBUG\"}");

        // Send a request to trigger logger initialization such as HttpResponseUtil logger.
        setHttp2TrafficLogLevel("{\"level\":\"TRACE\"}");
        // Revert the log level to null.
        setHttp2TrafficLogLevel("{\"level\":null}");

        final String previousAllLoggers = allLoggers();

        setHttp2TrafficLogLevel("{\"level\":\"TRACE\"}");
        assertThatJson(dogma.blockingHttpClient().get(
                "/api/v1/loggers/com.linecorp.armeria.logging.traffic.server.http2").contentUtf8())
                .isEqualTo("{\"name\":\"com.linecorp.armeria.logging.traffic.server.http2\"," +
                           " \"level\":\"TRACE\"," +
                           " \"effectiveLevel\":\"TRACE\"}");

        // Verify that the log level change is reflected in all loggers.
        assertThatJson(allLoggers()).isNotEqualTo(previousAllLoggers);
        setHttp2TrafficLogLevel("{\"level\":null}");
        assertThatJson(dogma.blockingHttpClient().get(
                "/api/v1/loggers/com.linecorp.armeria.logging.traffic.server.http2").contentUtf8())
                .isEqualTo("{\"name\":\"com.linecorp.armeria.logging.traffic.server.http2\"," +
                           " \"level\":null," +
                           " \"effectiveLevel\":\"DEBUG\"}");

        // Verify that the log level change is reverted in all loggers.
        assertThatJson(allLoggers()).isEqualTo(previousAllLoggers);
    }

    private static void setHttp2TrafficLogLevel(String body) {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.PUT,
                                       "/api/v1/loggers/com.linecorp.armeria.logging.traffic.server.http2")
                              .contentType(MediaType.JSON_UTF_8).build();
        assertThat(dogma.blockingHttpClient().execute(headers, body).status()).isSameAs(HttpStatus.OK);
    }

    String allLoggers() {
        final String allLoggers = dogma.blockingHttpClient().get("/api/v1/loggers").contentUtf8();
        assertThat(allLoggers)
                .contains("{\"name\":\"ROOT\",\"level\":\"WARN\",\"effectiveLevel\":\"WARN\"}",
                          "{\"name\":\"com\",\"level\":null,\"effectiveLevel\":\"WARN\"}",
                          "{\"name\":\"com.linecorp\",\"level\":\"DEBUG\",\"effectiveLevel\":\"DEBUG\"}");
        return allLoggers;
    }
}
