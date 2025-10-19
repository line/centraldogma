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

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.internal.api.converter.WatchRequestConverter.WatchRequest;

class WatchRequestConverterTest {

    private static final WatchRequestConverter converter = new WatchRequestConverter();

    @Test
    void extractValidRevision() {
        final String firstIfNoneMatch = "-1";
        final String firstRevision = converter.extractRevision(firstIfNoneMatch);
        assertThat(firstRevision).isEqualTo(firstIfNoneMatch);

        final String secondIfNoneMatch = "\"-1\"";
        final String secondRevision = converter.extractRevision(secondIfNoneMatch);
        assertThat(secondRevision).isEqualTo("-1");

        final String thirdIfNoneMatch = "W/\"-1\"";
        final String thirdRevision = converter.extractRevision(thirdIfNoneMatch);
        assertThat(thirdRevision).isEqualTo("-1");
    }

    @Test
    void extractInvalidRevision() {
        final String firstIfNoneMatch = "w/\"-1\"";
        final String firstRevision = converter.extractRevision(firstIfNoneMatch);
        assertThat(firstRevision).isEqualTo(firstIfNoneMatch);

        final String secondIfNoneMatch = "W\"-1\"";
        final String secondRevision = converter.extractRevision(secondIfNoneMatch);
        assertThat(secondRevision).isEqualTo(secondIfNoneMatch);

        final String thirdIfNoneMatch = "/\"-1\"";
        final String thirdRevision = converter.extractRevision(thirdIfNoneMatch);
        assertThat(thirdRevision).isEqualTo(thirdIfNoneMatch);

        final String fourthIfNoneMatch = "-1\"";
        final String fourthRevision = converter.extractRevision(fourthIfNoneMatch);
        assertThat(fourthRevision).isEqualTo(fourthIfNoneMatch);

        final String fifthIfNoneMatch = "\"-1";
        final String fifthRevision = converter.extractRevision(fifthIfNoneMatch);
        assertThat(fifthRevision).isEqualTo(fifthIfNoneMatch);

        final String sixthIfNoneMatch = "W/\"";
        final String sixthRevision = converter.extractRevision(sixthIfNoneMatch);
        assertThat(sixthRevision).isEqualTo(sixthIfNoneMatch);

        final String seventhIfNoneMatch = "\"";
        final String seventhRevision = converter.extractRevision(seventhIfNoneMatch);
        assertThat(seventhRevision).isEqualTo(seventhIfNoneMatch);

        final String eighthIfNoneMatch = "\"\"";
        final String eighthRevision = converter.extractRevision(eighthIfNoneMatch);
        assertThat(eighthRevision).isEqualTo(eighthIfNoneMatch);
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
