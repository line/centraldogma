/*
 * Copyright 2020 LINE Corporation
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
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
        protected void configureHttpClient(WebClientBuilder builder) {
            builder.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Test
    void status() {
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse res = client.get(API_V1_PATH_PREFIX + "status").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isEqualTo(
                "{ \"writable\": true, \"replicating\": true }");
    }

    @Test
    void updateStatus_setUnwritable() {
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse res = client.execute(
                RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                "[" + writable(false) + "]").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isEqualTo(
                "{ \"writable\": false, \"replicating\": true }");
    }

    @Test
    void updateStatus_setUnwritableAndNonReplicating() {
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse res = client.execute(
                RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                "[" + writable(false) + "," + replicating(false) + "]").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isEqualTo(
                "{ \"writable\": false, \"replicating\": false }");
    }

    @Test
    void updateStatus_setWritableAndNonReplicating() {
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse res = client.execute(
                RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                "[" + writable(true) + "," + replicating(false) + "]").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void redundantUpdateStatus_Writable() {
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse res = client.execute(
                RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status?scope=local",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                "[" + writable(true) + "]").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void redundantUpdateStatus_Replicating() {
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse res = client.execute(
                RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status?scope=local",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                "[" + replicating(true) + "]").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void updateStatus_leaveReadOnlyMode() {
        final WebClient client = dogma.httpClient();
        // Enter read-only mode.
        updateStatus_setUnwritable();
        // Try to enter writable mode.
        final AggregatedHttpResponse res = client.execute(
                RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                "[" + writable(true) + "]").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isEqualTo(
                "{ \"writable\": true, \"replicating\": true }");
    }

    @Test
    void updateStatus_enableReplicatingWithReadOnlyMode() {
        final WebClient client = dogma.httpClient();

        // Try to enter read-only mode with replication disabled.
        AggregatedHttpResponse res =
                client.execute(RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status",
                                                 HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                               "[" + writable(false) + "," + replicating(false) + "]")
                      .aggregate()
                      .join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isEqualTo("{ \"writable\": false, \"replicating\": false }");

        // Try to enable replication.
        res = client.execute(RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status",
                                               HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                             "[" + replicating(true) + "]")
                    .aggregate()
                    .join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isEqualTo("{ \"writable\": false, \"replicating\": true }");
    }

    @Test
    void updateStatus_disableReplicatingWithReadOnlyMode() {
        final WebClient client = dogma.httpClient();

        // Try to enter read-only mode with replication enabled.
        AggregatedHttpResponse res =
                client.execute(RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status",
                                                 HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                               "[" + writable(false) + "," + replicating(true) + "]")
                      .aggregate()
                      .join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isEqualTo("{ \"writable\": false, \"replicating\": true }");

        // Try to disable replication.
        res = client.execute(RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status",
                                               HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH),
                             "[" + replicating(false) + "]")
                    .aggregate()
                    .join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).isEqualTo("{ \"writable\": false, \"replicating\": false }");
    }

    private static String writable(boolean writable) {
        return "{ \"op\": \"replace\", \"path\": \"/writable\", \"value\": " + writable + " }";
    }

    private static String replicating(boolean replicating) {
        return "{ \"op\": \"replace\", \"path\": \"/replicating\", \"value\": " + replicating + " }";
    }
}
