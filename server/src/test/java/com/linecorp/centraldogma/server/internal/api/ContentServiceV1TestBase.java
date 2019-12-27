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

import java.net.InetSocketAddress;

import org.junit.Before;
import org.junit.Rule;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.junit4.CentralDogmaRule;

public class ContentServiceV1TestBase {

    @Rule
    public final CentralDogmaRule dogma = new CentralDogmaRule() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            // Shorten the default request timeout here, in order to do the following tests
            // that a watch request overrides the default request timeout.
            // - ContentServiceV1Test#watchRepositoryTimeout
            // - ContentServiceV1Test#watchFileTimeout
            builder.requestTimeoutMillis(3_000);
        }
    };

    static final String CONTENTS_PREFIX = "/api/v1/projects/myPro/repos/myRepo/contents";

    private WebClient webClient;

    @Before
    public void init() {
        final InetSocketAddress serverAddress = dogma.dogma().activePort().get().localAddress();
        final String serverUri = "http://127.0.0.1:" + serverAddress.getPort();
        webClient = WebClient.builder(serverUri)
                             .addHttpHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                             .build();

        // the default project used for unit tests
        RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/api/v1/projects",
                                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        String body = "{\"name\": \"myPro\"}";
        webClient.execute(headers, body).aggregate().join();

        // the default repository used for unit tests
        headers = RequestHeaders.of(HttpMethod.POST, "/api/v1/projects/myPro/repos",
                                    HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        body = "{\"name\": \"myRepo\"}";
        webClient.execute(headers, body).aggregate().join();
    }

    WebClient webClient() {
        return webClient;
    }
}
