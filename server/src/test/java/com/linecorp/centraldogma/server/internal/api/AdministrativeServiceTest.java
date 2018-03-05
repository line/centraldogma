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

package com.linecorp.centraldogma.server.internal.api;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class AdministrativeServiceTest {

    @Rule
    public final CentralDogmaRule dogma = new CentralDogmaRule();

    private static HttpClient httpClient;

    @Before
    public void init() {
        final InetSocketAddress serverAddress = dogma.dogma().activePort().get().localAddress();
        final String serverUri = "http://" + serverAddress.getHostString() + ':' + serverAddress.getPort();
        httpClient = new HttpClientBuilder(serverUri)
                .addHttpHeader(HttpHeaderNames.AUTHORIZATION, "bearer anonymous").build();
    }

    @Test
    public void status() {
        final AggregatedHttpMessage res = httpClient.get(API_V1_PATH_PREFIX + "status").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.content().toStringUtf8()).isEqualTo(
                "{ \"writable\": true }");
    }

    @Test
    public void updateStatus() {
        final AggregatedHttpMessage res = httpClient.execute(
                HttpHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status")
                           .contentType(MediaType.JSON_PATCH),
                "[{ \"op\": \"replace\", \"path\": \"/writable\", \"value\": false }]").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.content().toStringUtf8()).isEqualTo(
                "{ \"writable\": false }");
    }

    @Test
    public void redundantUpdateStatus() {
        final AggregatedHttpMessage res = httpClient.execute(
                HttpHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status")
                           .contentType(MediaType.JSON_PATCH),
                "[{ \"op\": \"replace\", \"path\": \"/writable\", \"value\": true }]").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    public void unimplementedUpdateStatus() {
        // Enter read-only mode.
        updateStatus();
        // Try to enter writable mode.
        final AggregatedHttpMessage res = httpClient.execute(
                HttpHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "status")
                           .contentType(MediaType.JSON_PATCH),
                "[{ \"op\": \"replace\", \"path\": \"/writable\", \"value\": true }]").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }
}
