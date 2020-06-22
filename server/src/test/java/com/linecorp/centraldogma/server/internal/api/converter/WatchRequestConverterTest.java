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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void convertWatchRequest() throws Exception {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final AggregatedHttpRequest request = mock(AggregatedHttpRequest.class);
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/",
                                                         HttpHeaderNames.IF_NONE_MATCH, "-1",
                                                         HttpHeaderNames.PREFER, "wait=10");
        when(request.headers()).thenReturn(headers);

        final WatchRequest watchRequest = convert(ctx, request);
        assertThat(watchRequest).isNotNull();
        assertThat(watchRequest.lastKnownRevision()).isEqualTo(Revision.HEAD);
        assertThat(watchRequest.timeoutMillis()).isEqualTo(10000); // 10 seconds
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
        return (WatchRequest) converter.convertRequest(ctx, request, null, null);
    }
}
