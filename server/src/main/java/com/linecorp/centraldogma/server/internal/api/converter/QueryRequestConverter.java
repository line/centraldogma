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

package com.linecorp.centraldogma.server.internal.api.converter;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.internal.Util.isValidFilePath;

import java.lang.reflect.ParameterizedType;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryType;

import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * A request converter that converts to {@link Query} when the request has a valid file path.
 */
public final class QueryRequestConverter implements RequestConverterFunction {

    /**
     * Converts the specified {@code request} to a {@link Query} when the request has a valid file path.
     * {@code null} otherwise.
     */
    @Override
    @Nullable
    public Query<?> convertRequest(
            ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
            @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {

        final String path = getPath(ctx);
        final Iterable<String> jsonPaths = getJsonPaths(ctx);
        if (jsonPaths != null) {
            return Query.ofJsonPath(path, jsonPaths);
        }

        if (isValidFilePath(path)) {
            return Query.of(QueryType.IDENTITY, path);
        }

        return null;
    }

    private static String getPath(ServiceRequestContext ctx) {
        // check the path param first
        final String path = ctx.pathParam("path");
        if (!isNullOrEmpty(path)) {
            return path;
        }

        // then check HTTP query
        final String query = ctx.query();
        if (query != null) {
            final List<String> params = new QueryStringDecoder(query, false).parameters().get("path");
            if (params != null) {
                return params.get(0);
            }
        }
        // return empty string if there's no path
        return "";
    }

    @Nullable
    private static Iterable<String> getJsonPaths(ServiceRequestContext ctx) {
        final String query = ctx.query();
        if (query != null) {
            final List<String> jsonPaths = new QueryStringDecoder(query, false).parameters().get(
                    "jsonpath");
            if (jsonPaths != null) {
                return ImmutableList.copyOf(jsonPaths);
            }
        }
        return null;
    }
}
