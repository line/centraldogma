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
import com.linecorp.centraldogma.server.internal.metadata.HolderWithLocation;

/**
 * A {@link ResponseConverterFunction} for the HTTP API which creates a resource.
 * This class adds the {@link HttpHeaderNames#LOCATION} with the url of the newly created resource.
 */
public final class CreateApiResponseConverter implements ResponseConverterFunction {

    private static final Logger logger = LoggerFactory.getLogger(CreateApiResponseConverter.class);

    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx, Object resObj) throws Exception {
        try {
            if (resObj instanceof HolderWithLocation) {
                return handleWithLocation((HolderWithLocation<?>) resObj);
            }

            final JsonNode jsonNode = Jackson.valueToTree(resObj);
            final String url = jsonNode.get("url").asText();

            // Remove the url field and send it with the LOCATION header.
            ((ObjectNode) jsonNode).remove("url");
            final HttpHeaders headers = HttpHeaders.of(HttpStatus.CREATED)
                                                   .add(HttpHeaderNames.LOCATION, url)
                                                   .contentType(MediaType.JSON_UTF_8);

            return HttpResponse.of(headers, HttpData.of(Jackson.writeValueAsBytes(jsonNode)));
        } catch (JsonProcessingException e) {
            logger.debug("Failed to convert a response:", e);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private HttpResponse handleWithLocation(HolderWithLocation<?> holderWithLocation)
            throws JsonProcessingException {
        return HttpResponse.of(
                HttpHeaders.of(HttpStatus.CREATED)
                           .add(HttpHeaderNames.LOCATION, holderWithLocation.location())
                           .contentType(MediaType.JSON_UTF_8),
                HttpData.of(Jackson.writeValueAsBytes(holderWithLocation.object())));
    }
}
