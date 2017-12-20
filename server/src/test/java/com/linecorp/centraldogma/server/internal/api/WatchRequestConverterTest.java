/*
 * Copyright 2017 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.Test;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.DefaultHttpHeaders;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.api.converter.WatchRequestConverter;
import com.linecorp.centraldogma.server.internal.api.converter.WatchRequestConverter.WatchRequest;

public class WatchRequestConverterTest {

    private static final WatchRequestConverter converter = new WatchRequestConverter();

    @Test
    public void convertWatchRequest() throws Exception {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final AggregatedHttpMessage request = mock(AggregatedHttpMessage.class);
        final HttpHeaders headers = new DefaultHttpHeaders().add(HttpHeaderNames.IF_NONE_MATCH, "-1")
                                                            .add(HttpHeaderNames.PREFER, "wait=10");

        when(request.headers()).thenReturn(headers);

        final Optional<WatchRequest> watchRequest = convert(ctx, request);
        assert watchRequest.isPresent();
        assertThat(watchRequest.get().lastKnownRevision()).isEqualTo(Revision.HEAD);
        assertThat(watchRequest.get().timeoutMillis()).isEqualTo(10000); // 10 seconds
    }

    @Test
    public void emptyHeader() throws Exception {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final AggregatedHttpMessage request = mock(AggregatedHttpMessage.class);
        final HttpHeaders headers = HttpHeaders.EMPTY_HEADERS;

        when(request.headers()).thenReturn(headers);

        final Optional<WatchRequest> watchRequest = convert(ctx, request);
        assertThat(watchRequest.isPresent()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static Optional<WatchRequest> convert(
            ServiceRequestContext ctx, AggregatedHttpMessage request) throws Exception {
        return (Optional<WatchRequest>) converter.convertRequest(ctx, request, null);
    }
}
