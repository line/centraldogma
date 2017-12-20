/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.centraldogma.server.internal.api;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.internal.Util.isValidFilePath;
import static com.linecorp.centraldogma.internal.Util.validateJsonFilePath;

import java.util.List;
import java.util.Optional;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.centraldogma.common.Query;

import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * A request converter that converts to {@link Query} when the request has a valid file path.
 */
final class RequestQueryConverter implements RequestConverterFunction {

    /**
     * Converts the specified {@code request} to {@link Optional} which contains {@link Query} when
     * the request has a valid file path. {@link Optional#EMPTY} otherwise.
     */
    @Override
    public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                 Class<?> expectedResultType) throws Exception {
        final String path = getPath(ctx);
        final Optional<String> expression = getExpression(ctx);
        if (expression.isPresent()) {
            validateJsonFilePath(path, "path");
            return Optional.of(Query.ofJsonPath(path, expression.get()));
        } else if (isValidFilePath(path)) {
            return Optional.of(Query.identity(path));
        }
        return Optional.empty();
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

    private static Optional<String> getExpression(ServiceRequestContext ctx) {
        final String query = ctx.query();
        if (query != null) {
            final List<String> expression = new QueryStringDecoder(query, false).parameters().get(
                    "expression");
            if (expression != null) {
                return Optional.of(expression.get(0));
            }
        }
        return Optional.empty();
    }
}
