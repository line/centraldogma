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

package com.linecorp.centraldogma.server.internal.api.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.api.converter.WatchRequestConverter.WatchRequest;

class WatchRequestConverterTest {

    private static final WatchRequestConverter converter = new WatchRequestConverter();

    @Test
    void convertRequestWithValidInNoneMatcherHeaderToWatchRequest() throws Exception {
        final RequestHeaders firstHeaders = RequestHeaders.of(HttpMethod.GET, "/",
                                                              HttpHeaderNames.IF_NONE_MATCH, "-1",
                                                              HttpHeaderNames.PREFER, "wait=10");
        final AggregatedHttpRequest firstRequest = AggregatedHttpRequest.of(firstHeaders);
        final ServiceRequestContext firstCtx = ServiceRequestContext.of(firstRequest.toHttpRequest());

        final WatchRequest firstWatchRequest = convert(firstCtx, firstRequest);
        assertThat(firstWatchRequest).isNotNull();
        assertThat(firstWatchRequest.lastKnownRevision()).isEqualTo(Revision.HEAD);
        assertThat(firstWatchRequest.timeoutMillis()).isEqualTo(10000); // 10 seconds

        final RequestHeaders secondHeaders = RequestHeaders.of(HttpMethod.GET, "/",
                                                               HttpHeaderNames.IF_NONE_MATCH, "\"-1\"",
                                                               HttpHeaderNames.PREFER, "wait=10");
        final AggregatedHttpRequest secondRequest = AggregatedHttpRequest.of(secondHeaders);
        final ServiceRequestContext secondCtx = ServiceRequestContext.of(secondRequest.toHttpRequest());

        final WatchRequest secondWatchRequest = convert(secondCtx, secondRequest);
        assertThat(secondWatchRequest).isNotNull();
        assertThat(secondWatchRequest.lastKnownRevision()).isEqualTo(Revision.HEAD);
        assertThat(secondWatchRequest.timeoutMillis()).isEqualTo(10000); // 10 seconds

        final RequestHeaders thirdHeaders = RequestHeaders.of(HttpMethod.GET, "/",
                                                              HttpHeaderNames.IF_NONE_MATCH, "W/\"-1\"",
                                                              HttpHeaderNames.PREFER, "wait=10");
        final AggregatedHttpRequest thirdRequest = AggregatedHttpRequest.of(thirdHeaders);
        final ServiceRequestContext thirdCtx = ServiceRequestContext.of(thirdRequest.toHttpRequest());

        final WatchRequest thirdWatchRequest = convert(thirdCtx, thirdRequest);
        assertThat(thirdWatchRequest).isNotNull();
        assertThat(thirdWatchRequest.lastKnownRevision()).isEqualTo(Revision.HEAD);
        assertThat(thirdWatchRequest.timeoutMillis()).isEqualTo(10000); // 10 seconds
    }

    @Test
    void convertRequestWithInvalidInNoneMatcherHeaderToWatchRequest() {
        final RequestHeaders firstInvalidHeaders =
                RequestHeaders.of(HttpMethod.GET, "/",
                                  HttpHeaderNames.IF_NONE_MATCH, "w/\"-1\"",
                                  HttpHeaderNames.PREFER, "wait=10");
        final AggregatedHttpRequest firstInvalidRequest = AggregatedHttpRequest.of(firstInvalidHeaders);
        final ServiceRequestContext firstCtx = ServiceRequestContext.of(firstInvalidRequest.toHttpRequest());

        assertThatThrownBy(() -> convert(firstCtx, firstInvalidRequest))
                .isInstanceOf(IllegalArgumentException.class);

        final RequestHeaders secondInvalidHeaders =
                RequestHeaders.of(HttpMethod.GET, "/",
                                  HttpHeaderNames.IF_NONE_MATCH, "W\"-1\"",
                                  HttpHeaderNames.PREFER, "wait=10");
        final AggregatedHttpRequest secondInvalidRequest = AggregatedHttpRequest.of(secondInvalidHeaders);
        final ServiceRequestContext secondCtx = ServiceRequestContext.of(secondInvalidRequest.toHttpRequest());

        assertThatThrownBy(() -> convert(secondCtx, secondInvalidRequest))
                .isInstanceOf(IllegalArgumentException.class);

        final RequestHeaders thirdInvalidHeaders =
                RequestHeaders.of(HttpMethod.GET, "/",
                                  HttpHeaderNames.IF_NONE_MATCH, "/\"-1\"",
                                  HttpHeaderNames.PREFER, "wait=10");
        final AggregatedHttpRequest thirdInvalidRequest = AggregatedHttpRequest.of(thirdInvalidHeaders);
        final ServiceRequestContext thirdCtx = ServiceRequestContext.of(thirdInvalidRequest.toHttpRequest());

        assertThatThrownBy(() -> convert(thirdCtx, thirdInvalidRequest))
                .isInstanceOf(IllegalArgumentException.class);

        final RequestHeaders fourthInvalidHeaders =
                RequestHeaders.of(HttpMethod.GET, "/",
                                  HttpHeaderNames.IF_NONE_MATCH, "-1\"",
                                  HttpHeaderNames.PREFER, "wait=10");
        final AggregatedHttpRequest fourthInvalidRequest = AggregatedHttpRequest.of(fourthInvalidHeaders);
        final ServiceRequestContext fourthCtx = ServiceRequestContext.of(fourthInvalidRequest.toHttpRequest());

        assertThatThrownBy(() -> convert(fourthCtx, fourthInvalidRequest))
                .isInstanceOf(IllegalArgumentException.class);

        final RequestHeaders fifthInvalidHeaders =
                RequestHeaders.of(HttpMethod.GET, "/",
                                  HttpHeaderNames.IF_NONE_MATCH, "\"-1",
                                  HttpHeaderNames.PREFER, "wait=10");
        final AggregatedHttpRequest fifthInvalidRequest = AggregatedHttpRequest.of(fifthInvalidHeaders);
        final ServiceRequestContext fifthCtx = ServiceRequestContext.of(fifthInvalidRequest.toHttpRequest());

        assertThatThrownBy(() -> convert(fifthCtx, fifthInvalidRequest))
                .isInstanceOf(IllegalArgumentException.class);

        final RequestHeaders sixthInvalidHeaders =
                RequestHeaders.of(HttpMethod.GET, "/",
                                  HttpHeaderNames.IF_NONE_MATCH, "W/\"",
                                  HttpHeaderNames.PREFER, "wait=10");
        final AggregatedHttpRequest sixthInvalidRequest = AggregatedHttpRequest.of(sixthInvalidHeaders);
        final ServiceRequestContext sixthCtx = ServiceRequestContext.of(sixthInvalidRequest.toHttpRequest());

        assertThatThrownBy(() -> convert(sixthCtx, sixthInvalidRequest))
                .isInstanceOf(IllegalArgumentException.class);

        final RequestHeaders seventhInvalidHeaders =
                RequestHeaders.of(HttpMethod.GET, "/",
                                  HttpHeaderNames.IF_NONE_MATCH, "\"",
                                  HttpHeaderNames.PREFER, "wait=10");
        final AggregatedHttpRequest seventhInvalidRequest = AggregatedHttpRequest.of(seventhInvalidHeaders);
        final ServiceRequestContext seventhCtx =
                ServiceRequestContext.of(seventhInvalidRequest.toHttpRequest());

        assertThatThrownBy(() -> convert(seventhCtx, seventhInvalidRequest))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyHeader() throws Exception {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/");
        final AggregatedHttpRequest request = AggregatedHttpRequest.of(headers);
        final ServiceRequestContext ctx = ServiceRequestContext.of(request.toHttpRequest());

        final WatchRequest watchRequest = convert(ctx, request);
        assertThat(watchRequest).isNull();
    }

    @Nullable
    private static WatchRequest convert(
            ServiceRequestContext ctx, AggregatedHttpRequest request) throws Exception {
        return converter.convertRequest(ctx, request, null, null);
    }
}
