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

import java.lang.reflect.ParameterizedType;

import javax.annotation.Nullable;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.centraldogma.common.MergeQuery;
import com.linecorp.centraldogma.common.MergeSource;

import io.netty.handler.codec.http.QueryStringDecoder;

public class MergeQueryRequestConverter implements RequestConverterFunction {

    private static final Splitter querySplitter = Splitter.on('&').trimResults().omitEmptyStrings();

    @Override
    public MergeQuery<?> convertRequest(
            ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
            @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {

        final String queryString = ctx.query();
        if (queryString != null) {
            // Decode queryString manually so that the original order of "path" and "optional_path" is
            // preserved. For example if a user specifies the query as
            // "path=/a.json&optional_path=b.json&path=c.json", the mergeQuery will merge the files
            // in the order of "/a.json", "/b.json" and "/c.json".
            final String decodedString = QueryStringDecoder.decodeComponent(queryString);
            final Iterable<String> queries = querySplitter.split(decodedString);
            final Builder<MergeSource> mergeSourceBuilder = ImmutableList.builder();
            final Builder<String> jsonPathsBuilder = ImmutableList.builder();
            for (String query : queries) {
                final int index = query.indexOf('=');
                if (index < 0) {
                    continue;
                }
                final String key = query.substring(0, index);
                final String value = query.substring(index + 1);
                switch (key) {
                    case "path":
                        mergeSourceBuilder.add(MergeSource.ofRequired(value));
                        break;
                    case "optional_path":
                        mergeSourceBuilder.add(MergeSource.ofOptional(value));
                        break;
                    case "jsonpath":
                        jsonPathsBuilder.add(value);
                        break;
                }
            }

            return MergeQuery.ofJsonPath(mergeSourceBuilder.build(), jsonPathsBuilder.build());
        }
        return RequestConverterFunction.fallthrough();
    }
}
