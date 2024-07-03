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

package com.linecorp.centraldogma.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.CorsConfig;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.netty.util.AsciiString;

class CorsPolicyTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.cors(new CorsConfig(ImmutableList.of("SomeOrigin"), 1800));
        }
    };

    @Test
    void testCorsHeadersAvailable() throws Exception {
        final WebClient client = dogma.httpClient();

        final AggregatedHttpResponse res =
                client.blocking()
                      .prepare()
                      .header(HttpHeaderNames.ORIGIN, "SomeOrigin")
                      .header(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                      .options("/api/v1/projects")
                      .execute();
        final ResponseHeaders headers = res.headers();

        assertThat(headers).contains(entry(AsciiString.of("access-control-allow-origin"), "SomeOrigin"));
        assertThat(headers).contains(entry(AsciiString.of("access-control-allow-credentials"), "true"));
        assertThat(headers).contains(entry(AsciiString.of("access-control-max-age"), "1800"));
    }

    @Test
    void testCorsHeadersUnAvailable() throws Exception {
        final WebClient client = dogma.httpClient();

        final AggregatedHttpResponse res =
                client.blocking()
                      .prepare()
                      .header(HttpHeaderNames.ORIGIN, "SomeOriginNotIncluded")
                      .header(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                      .options("/api/v1/projects")
                      .execute();
        final ResponseHeaders headers = res.headers();

        assertThat(headers.get("access-control-allow-origin")).isNull();
        assertThat(headers.get("access-control-allow-credentials")).isNull();
        assertThat(headers.get("access-control-max-age")).isNull();
    }
}
