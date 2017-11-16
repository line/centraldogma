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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.centraldogma.internal.Util.isValidFilePath;
import static com.linecorp.centraldogma.internal.Util.validateJsonFilePath;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.QueryType;
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
     * Returns the {@link JsonNode} from the specified {@link AggregatedHttpMessage}.
     *
     * @throws HttpResponseException if failed to read json
     */
    static JsonNode getJsonNode(AggregatedHttpMessage message) {
        try {
            final JsonNode jsonNode = Jackson.readTree(message.content().toStringUtf8());
            checkArgument(jsonNode != null && !jsonNode.isNull(), "invalid JSON: " + message.content());
            return jsonNode;
        } catch (IOException e) {
            throw newHttpResponseException(HttpStatus.BAD_REQUEST, "invalid JSON: " + message.content());
        }
    }

    /**
     * Returns an {@link Optional} which contains a {@link Query} when the path is valid file path.
     * {@link Optional#EMPTY} otherwise.
     *
     * @throws IllegalArgumentException if the {@code type} is {@link QueryType#JSON_PATH} and the path is
     *                                  not a valid json file path.
     */
    static Optional<Query<?>> getQueryIfPathIsValidFile(QueryType type, String path, String expression) {
        requireNonNull(type, "type");
        requireNonNull(path, "path");
        requireNonNull(expression, "expression");

        if (type == QueryType.JSON_PATH) {
            // JSON_PATH query with not a valid JSON file path
            validateJsonFilePath(path, "path");
            return Optional.of(Query.of(type, path, expression));
        } else if (isValidFilePath(path)) {
            return Optional.of(Query.of(type, path, expression));
        } else {
            return Optional.empty();
        }
    }

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
        final String content = "{\"message\":\"" + message + "\"}";
        return HttpResponse.of(status, MediaType.JSON_UTF_8, content);
    }

    private HttpApiV1Util() {}
}
