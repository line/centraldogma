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
package com.linecorp.centraldogma.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.CorsConfig;

class AuthProviderTest {

    @Test
    void webLoginService() throws Exception {
        final AuthProvider authProvider = () -> null;
        final HttpService webLoginService =
                authProvider.webLoginService(AllowedUrisConfig.of(
                        new CorsConfig(ImmutableList.of("https://example.com"), null)));

        String returnTo = "https://example.com";
        HttpResponse response = redirectResponse(returnTo, webLoginService);
        assertThat(response.aggregate().join().headers().get(HttpHeaderNames.LOCATION))
                .isEqualTo("https://example.com/web/auth/login");

        returnTo = "https://phishing.com";
        response = redirectResponse(returnTo, webLoginService);
        assertThat(response.aggregate().join().headers().get(HttpHeaderNames.LOCATION))
                .isEqualTo("/web/auth/login");
    }

    private static HttpResponse redirectResponse(String returnTo, HttpService webLoginService)
            throws Exception {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.POST, "/link/auth/login?return_to=" + returnTo)
                              .build();
        final HttpRequest request = HttpRequest.of(headers);
        final ServiceRequestContext ctx = ServiceRequestContext.of(request);
        return webLoginService.serve(ctx, request);
    }
}
