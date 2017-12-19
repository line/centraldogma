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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.centraldogma.internal.Jackson;

/**
 * A utility class which provides common functions for HTTP API version 1.
 */
final class HttpApiV1Util {

    static final JsonNode unremovePatch =
            JsonNodeFactory.instance.arrayNode().add(
                    JsonNodeFactory.instance
                            .objectNode().put("op", "replace").put("path", "/status").put("value", "active"));

    /**
     * Returns a newly created {@link HttpResponseException} with the specified {@link HttpStatus} and
     * {@code message}.
     */
    static HttpResponseException newHttpResponseException(HttpStatus status, String message) {
        return HttpResponseException.of(newResponseWithErrorMessage(status, message));
    }

    /**
     * Returns a newly created {@link HttpResponse} with the specified {@link HttpStatus} and {@code message}.
     */
    static HttpResponse newResponseWithErrorMessage(HttpStatus status, String message) {
        // TODO(minwoox) refine the error message
        final ObjectNode content = JsonNodeFactory.instance.objectNode().put("message", message);
        try {
            return HttpResponse.of(status, MediaType.JSON_UTF_8, Jackson.writeValueAsBytes(content));
        } catch (JsonProcessingException e) {
            // should not reach here
            throw new Error(e);
        }
    }

    private HttpApiV1Util() {}
}
