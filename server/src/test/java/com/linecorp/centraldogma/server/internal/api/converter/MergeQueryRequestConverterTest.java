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

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergeSource;

class MergeQueryRequestConverterTest {

    private static final MergeQueryRequestConverter converter = new MergeQueryRequestConverter();

    @Test
    void convert() throws Exception {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final String queryString = "path=/foo.json" + '&' +
                                   "pa%22th=/foo1.json" + '&' +
                                   "optional_path=/foo2.json" + '&' +
                                   "path=/foo3.json" + '&' +
                                   "jsonpath=$.a" + '&' +
                                   "revision=9999";
        when(ctx.query()).thenReturn(queryString);
        @SuppressWarnings("unchecked")
        final MergeQuery<JsonNode> mergeQuery =
                (MergeQuery<JsonNode>) converter.convertRequest(
                        ctx, mock(AggregatedHttpRequest.class), null, null);
        assertThat(mergeQuery).isEqualTo(
                MergeQuery.ofJsonPath(
                        ImmutableList.of(MergeSource.ofRequired("/foo.json"),
                                         MergeSource.ofOptional("/foo2.json"),
                                         MergeSource.ofRequired("/foo3.json")),
                        ImmutableList.of("$.a")));
    }
}
