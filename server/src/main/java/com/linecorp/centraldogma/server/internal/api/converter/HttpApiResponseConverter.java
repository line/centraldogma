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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.centraldogma.internal.Jackson;

/**
 * A default {@link ResponseConverterFunction} of HTTP API.
 */
public final class HttpApiResponseConverter implements ResponseConverterFunction {

    private static final Logger logger = LoggerFactory.getLogger(HttpApiResponseConverter.class);

    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx, Object resObj) throws Exception {
        try {
            final HttpRequest request = RequestContext.current().request();
            if (HttpMethod.DELETE == request.method() ||
                (resObj instanceof Iterable && Iterables.size((Iterable) resObj) == 0)) {
                return HttpResponse.of(HttpStatus.NO_CONTENT);
            }

            final HttpData httpData = HttpData.of(Jackson.writeValueAsBytes(resObj));
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, httpData);
        } catch (JsonProcessingException e) {
            logger.debug("Failed to convert a response:", e);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
