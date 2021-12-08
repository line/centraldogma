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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
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
        final HttpRequest firstRequest = HttpRequest.of(firstHeaders);
        final ServiceRequestContext firstCtx = ServiceRequestContext.of(firstRequest);

        final WatchRequest firstWatchRequest = convert(firstCtx, firstRequest.aggregate().join());
        assertThat(firstWatchRequest).isNotNull();
        assertThat(firstWatchRequest.lastKnownRevision()).isEqualTo(Revision.HEAD);
        assertThat(firstWatchRequest.timeoutMillis()).isEqualTo(10000); // 10 seconds

        final RequestHeaders secondHeaders = RequestHeaders.of(HttpMethod.GET, "/",
                                                               HttpHeaderNames.IF_NONE_MATCH, "\"-1\"",
                                                               HttpHeaderNames.PREFER, "wait=10");
        final HttpRequest secondRequest = HttpRequest.of(secondHeaders);
        final ServiceRequestContext secondCtx = ServiceRequestContext.of(secondRequest);

        final WatchRequest secondWatchRequest = convert(secondCtx, secondRequest.aggregate().join());
        assertThat(secondWatchRequest).isNotNull();
        assertThat(secondWatchRequest.lastKnownRevision()).isEqualTo(Revision.HEAD);
        assertThat(secondWatchRequest.timeoutMillis()).isEqualTo(10000); // 10 seconds

        final RequestHeaders thirdHeaders = RequestHeaders.of(HttpMethod.GET, "/",
                                                              HttpHeaderNames.IF_NONE_MATCH, "W/\"-1\"",
                                                              HttpHeaderNames.PREFER, "wait=10");
        final HttpRequest thirdRequest = HttpRequest.of(thirdHeaders);
        final ServiceRequestContext thirdCtx = ServiceRequestContext.of(thirdRequest);

        final WatchRequest thirdWatchRequest = convert(thirdCtx, thirdRequest.aggregate().join());
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
        final HttpRequest firstInvalidRequest = HttpRequest.of(firstInvalidHeaders);
        final ServiceRequestContext firstCtx = ServiceRequestContext.of(firstInvalidRequest);

        assertThatThrownBy(() -> convert(firstCtx, firstInvalidRequest.aggregate().join()))
                .isInstanceOf(IllegalArgumentException.class);

        final RequestHeaders secondInvalidHeaders =
                RequestHeaders.of(HttpMethod.GET, "/",
                                  HttpHeaderNames.IF_NONE_MATCH, "W\"-1\"",
                                  HttpHeaderNames.PREFER, "wait=10");
        final HttpRequest secondInvalidRequest = HttpRequest.of(secondInvalidHeaders);
        final ServiceRequestContext secondCtx = ServiceRequestContext.of(secondInvalidRequest);

        assertThatThrownBy(() -> convert(secondCtx, secondInvalidRequest.aggregate().join()))
                .isInstanceOf(IllegalArgumentException.class);

        final RequestHeaders thirdInvalidHeaders =
                RequestHeaders.of(HttpMethod.GET, "/",
                                  HttpHeaderNames.IF_NONE_MATCH, "/\"-1\"",
                                  HttpHeaderNames.PREFER, "wait=10");
        final HttpRequest thirdInvalidRequest = HttpRequest.of(thirdInvalidHeaders);
        final ServiceRequestContext thirdCtx = ServiceRequestContext.of(thirdInvalidRequest);

        assertThatThrownBy(() -> convert(thirdCtx, thirdInvalidRequest.aggregate().join()))
                .isInstanceOf(IllegalArgumentException.class);

        final RequestHeaders fourthInvalidHeaders =
                RequestHeaders.of(HttpMethod.GET, "/",
                                  HttpHeaderNames.IF_NONE_MATCH, "-1\"",
                                  HttpHeaderNames.PREFER, "wait=10");
        final HttpRequest fourthInvalidRequest = HttpRequest.of(fourthInvalidHeaders);
        final ServiceRequestContext fourthCtx = ServiceRequestContext.of(fourthInvalidRequest);

        assertThatThrownBy(() -> convert(fourthCtx, fourthInvalidRequest.aggregate().join()))
                .isInstanceOf(IllegalArgumentException.class);

        final RequestHeaders fifthInvalidHeaders =
                RequestHeaders.of(HttpMethod.GET, "/",
                                  HttpHeaderNames.IF_NONE_MATCH, "\"-1",
                                  HttpHeaderNames.PREFER, "wait=10");
        final HttpRequest fifthInvalidRequest = HttpRequest.of(fifthInvalidHeaders);
        final ServiceRequestContext fifthCtx = ServiceRequestContext.of(fifthInvalidRequest);

        assertThatThrownBy(() -> convert(fifthCtx, fifthInvalidRequest.aggregate().join()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyHeader() throws Exception {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final AggregatedHttpRequest request = mock(AggregatedHttpRequest.class);
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/");

        when(request.headers()).thenReturn(headers);

        final WatchRequest watchRequest = convert(ctx, request);
        assertThat(watchRequest).isNull();
    }

    @Nullable
    private static WatchRequest convert(
            ServiceRequestContext ctx, AggregatedHttpRequest request) throws Exception {
        return converter.convertRequest(ctx, request, null, null);
    }
}
