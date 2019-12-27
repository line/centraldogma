/*
 * Copyright 2019 LINE Corporation
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

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class AdministrativeServiceTest {

    @RegisterExtension
    final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    private static WebClient webClient;

    @BeforeEach
    void setUp() {
        final InetSocketAddress serverAddress = dogma.dogma().activePort().get().localAddress();
        final String serverUri = "http://127.0.0.1:" + serverAddress.getPort();
        webClient = WebClient.builder(serverUri)
                             .addHttpHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                             .build();
    }

    @Test
    void status() {
        final AggregatedHttpResponse res = webClient.get(API_V1_PATH_PREFIX + "status").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isEqualTo(
                "{ \"writable\": true, \"replicating\": true }");
    }

    @Test
    void updateStatus_setUnwritable() {
        final AggregatedHttpResponse res = webClient.execute(
                RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                "[{ \"op\": \"replace\", \"path\": \"/writable\", \"value\": false }]").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isEqualTo(
                "{ \"writable\": false, \"replicating\": true }");
    }

    @Test
    void updateStatus_setUnwritableAndNonReplicating() {
        final AggregatedHttpResponse res = webClient.execute(
                RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                "[{ \"op\": \"replace\", \"path\": \"/writable\", \"value\": false }," +
                " { \"op\": \"replace\", \"path\": \"/replicating\", \"value\": false }]").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isEqualTo(
                "{ \"writable\": false, \"replicating\": false }");
    }

    @Test
    void updateStatus_setWritableAndNonReplicating() {
        final AggregatedHttpResponse res = webClient.execute(
                RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                "[{ \"op\": \"replace\", \"path\": \"/writable\", \"value\": true }," +
                " { \"op\": \"replace\", \"path\": \"/replicating\", \"value\": false }]").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void redundantUpdateStatus_Writable() {
        final AggregatedHttpResponse res = webClient.execute(
                RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                "[{ \"op\": \"replace\", \"path\": \"/writable\", \"value\": true }]").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void redundantUpdateStatus_Replicating() {
        final AggregatedHttpResponse res = webClient.execute(
                RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                "[{ \"op\": \"replace\", \"path\": \"/replicating\", \"value\": true }]").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void updateStatus_leaveReadOnlyMode() {
        // Enter read-only mode.
        updateStatus_setUnwritable();
        // Try to enter writable mode.
        final AggregatedHttpResponse res = webClient.execute(
                RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                "[{ \"op\": \"replace\", \"path\": \"/writable\", \"value\": true }]").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isEqualTo(
                "{ \"writable\": true, \"replicating\": true }");
    }
}
