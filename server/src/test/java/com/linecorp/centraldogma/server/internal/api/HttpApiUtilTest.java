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

package com.linecorp.centraldogma.server.internal.api;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;

class HttpApiUtilTest {

    @Test
    void shouldPeelUnnecessaryExceptions() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        final CompletionException redundant =
                new CompletionException(new IllegalArgumentException("Invalid input"));
        final HttpResponse httpResponse = HttpApiUtil.newResponse(ctx, status, redundant);
        final AggregatedHttpResponse response = httpResponse.aggregate().join();
        assertThatJson(response.contentUtf8())
                .isEqualTo("{\"exception\":\"java.lang.IllegalArgumentException\"," +
                           "\"message\":\"Invalid input\"}");
    }
}
