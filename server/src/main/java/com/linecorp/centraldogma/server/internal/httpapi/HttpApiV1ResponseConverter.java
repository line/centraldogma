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

package com.linecorp.centraldogma.server.internal.httpapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.centraldogma.internal.Jackson;

/**
 * A default response converter of HTTP API version 1.
 */
public class HttpApiV1ResponseConverter implements ResponseConverter {

    @Override
    public HttpResponse convert(Object resObj) throws Exception {
        try {
            final HttpRequest request = RequestContext.current().request();

            if (HttpMethod.POST == request.method()) {
                final JsonNode jsonNode = Jackson.valueToTree(resObj);
                final String url = jsonNode.get("url").asText();

                // Remove the url field and send it with the LOCATION header.
                ((ObjectNode) jsonNode).remove("url");
                final HttpHeaders headers = HttpHeaders.of(HttpStatus.CREATED)
                                                       .add(HttpHeaderNames.LOCATION, url)
                                                       .addObject(HttpHeaderNames.CONTENT_TYPE,
                                                                  MediaType.JSON_UTF_8);
                final AggregatedHttpMessage aRes =
                        AggregatedHttpMessage.of(headers, HttpData.of(Jackson.writeValueAsBytes(jsonNode)));
                return aRes.toHttpResponse();
            }

            if (HttpMethod.DELETE == request.method()) {
                return HttpResponse.of(HttpStatus.NO_CONTENT);
            }

            final HttpData httpData = HttpData.of(Jackson.writeValueAsBytes(resObj));
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, httpData);
        } catch (JsonProcessingException e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
