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

package com.linecorp.centraldogma.server.internal.api.converter;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.centraldogma.common.MergerQuery;
import com.linecorp.centraldogma.common.PathAndOptional;

import io.netty.handler.codec.http.QueryStringDecoder;

public class MergerQueryRequestConverter implements RequestConverterFunction {

    private static final Splitter querySplitter = Splitter.on('&').trimResults().omitEmptyStrings();

    private static final Splitter keyValueSplitter = Splitter.on('=').trimResults().omitEmptyStrings();

    @Nullable
    @Override
    public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                 Class<?> expectedResultType) throws Exception {
        final String queryString = ctx.query();
        if (queryString != null) {
            final String decodeString = QueryStringDecoder.decodeComponent(queryString);
            final Iterable<String> queries = querySplitter.split(decodeString);

            final Builder<PathAndOptional> builder = ImmutableList.builder();
            for (String query : queries) {
                final Iterable<String> keyValue = keyValueSplitter.split(query);
                if (Iterables.size(keyValue) != 2) {
                    continue;
                }
                if ("path".equals(Iterables.get(keyValue, 0))) {
                    builder.add(new PathAndOptional(Iterables.get(keyValue, 1), false));
                } else if ("optional_path".equals(Iterables.get(keyValue, 0))) {
                    builder.add(new PathAndOptional(Iterables.get(keyValue, 1), true));
                }
            }

            final List<String> jsonPaths = new QueryStringDecoder(queryString, false).parameters()
                                                                                     .get("jsonpath");
            return MergerQuery.ofJsonPath(builder.build(), jsonPaths != null ? jsonPaths
                                                                             : ImmutableList.of());
        }
        return RequestConverterFunction.fallthrough();
    }
}
