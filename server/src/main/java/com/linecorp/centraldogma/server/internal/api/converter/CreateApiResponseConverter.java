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

import static com.linecorp.armeria.internal.annotation.ResponseConversionUtil.toMutableHeaders;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;

/**
 * A {@link ResponseConverterFunction} for the HTTP API which creates a resource.
 * This class adds the {@link HttpHeaderNames#LOCATION} with the url of the newly created resource.
 */
public final class CreateApiResponseConverter implements ResponseConverterFunction {

    private static final Logger logger = LoggerFactory.getLogger(CreateApiResponseConverter.class);

    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx, HttpHeaders headers,
                                        @Nullable Object resObj,
                                        HttpHeaders trailingHeaders) throws Exception {
        try {
            final HttpHeaders httpHeaders = toMutableHeaders(headers);
            if (httpHeaders.contentType() == null) {
                httpHeaders.contentType(MediaType.JSON_UTF_8);
            }

            final JsonNode jsonNode = Jackson.valueToTree(resObj);
            if (httpHeaders.get(HttpHeaderNames.LOCATION) == null) {
                final String url = jsonNode.get("url").asText();

                // Remove the url field and send it with the LOCATION header.
                ((ObjectNode) jsonNode).remove("url");
                httpHeaders.add(HttpHeaderNames.LOCATION, url);
            }

            return HttpResponse.of(httpHeaders, HttpData.of(Jackson.writeValueAsBytes(jsonNode)),
                                   trailingHeaders);
        } catch (JsonProcessingException e) {
            logger.debug("Failed to convert a response:", e);
            return HttpApiUtil.newResponse(ctx, HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }
}
