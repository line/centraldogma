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

package com.linecorp.centraldogma.server;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.ShuttingDownException;

class CentralDogmaAuthFailureHandlerTest {

    private final CentralDogmaAuthFailureHandler handler = new CentralDogmaAuthFailureHandler();
    @SuppressWarnings("unchecked")
    private final HttpService delegate = mock(HttpService.class);
    private final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
    private final ServiceRequestContext ctx = ServiceRequestContext.of(req);

    @Test
    void shuttingDown() throws Exception {
        final AggregatedHttpResponse res = handler.authFailed(delegate, ctx, req, new ShuttingDownException())
                                                  .aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8()).isEqualTo(
                '{' +
                "  \"exception\": \"com.linecorp.centraldogma.common.ShuttingDownException\"," +
                "  \"message\":\"\"" +
                '}');
    }

    @Test
    void failure() throws Exception {
        final AggregatedHttpResponse res = handler.authFailed(delegate, ctx, req, new Exception("oops"))
                                                  .aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8()).isEqualTo(
                '{' +
                "  \"exception\": \"java.lang.Exception\"," +
                "  \"message\":\"oops\"" +
                '}');
    }

    @Test
    void incorrectToken() throws Exception {
        final AggregatedHttpResponse res = handler.authFailed(delegate, ctx, req, null)
                                                  .aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8()).isEqualTo(
                '{' +
                "  \"exception\": \"com.linecorp.centraldogma.common.AuthorizationException\"," +
                "  \"message\":\"\"" +
                '}');
    }
}
