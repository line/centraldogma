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

package com.linecorp.centraldogma.server.internal.api;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.centraldogma.internal.Jackson;

/**
 * A utility class which provides common functions for HTTP API.
 */
//TODO(minwoox) change this class to package-local when the admin API is integrated with HTTP API
public final class HttpApiUtil {

    static final JsonNode unremoveRequest = Jackson.valueToTree(
            ImmutableList.of(
                    ImmutableMap.of("op", "replace",
                                    "path", "/status",
                                    "value", "active")));

    /**
     * Throws a newly created {@link HttpResponseException} with the specified {@link HttpStatus} and
     * {@code message}.
     */
    public static <T> T throwResponse(HttpStatus status, String message) {
        throw HttpResponseException.of(newResponse(status, message));
    }

    /**
     * Throws a newly created {@link HttpResponseException} with the specified {@link HttpStatus} and
     * the formatted message.
     */
    public static <T> T throwResponse(HttpStatus status, String format, Object... args) {
        throw HttpResponseException.of(newResponse(status, format, args));
    }

    /**
     * Throws a newly created {@link HttpResponseException} with the specified {@link HttpStatus},
     * {@code cause} and {@code message}.
     */
    public static <T> T throwResponse(HttpStatus status, Throwable cause, String message) {
        throw HttpResponseException.of(newResponse(status, cause, message));
    }

    /**
     * Throws a newly created {@link HttpResponseException} with the specified {@link HttpStatus},
     * {@code cause} and the formatted message.
     */
    public static <T> T throwResponse(HttpStatus status, Throwable cause, String format, Object... args) {
        throw HttpResponseException.of(newResponse(status, cause, format, args));
    }

    /**
     * Returns a newly created {@link HttpResponse} with the specified {@link HttpStatus} and the formatted
     * message.
     */
    public static HttpResponse newResponse(HttpStatus status, String format, Object... args) {
        requireNonNull(status, "status");
        requireNonNull(format, "format");
        requireNonNull(args, "args");
        return newResponse(status, String.format(Locale.ENGLISH, format, args));
    }

    /**
     * Returns a newly created {@link HttpResponse} with the specified {@link HttpStatus} and {@code message}.
     */
    public static HttpResponse newResponse(HttpStatus status, String message) {
        requireNonNull(status, "status");
        requireNonNull(message, "message");
        return newResponse0(status, null, message);
    }

    /**
     * Returns a newly created {@link HttpResponse} with the specified {@link HttpStatus} and {@code cause}.
     */
    public static HttpResponse newResponse(HttpStatus status, Throwable cause) {
        requireNonNull(status, "status");
        requireNonNull(cause, "cause");
        return newResponse0(status, cause, null);
    }

    /**
     * Returns a newly created {@link HttpResponse} with the specified {@link HttpStatus}, {@code cause} and
     * the formatted message.
     */
    public static HttpResponse newResponse(HttpStatus status, Throwable cause,
                                           String format, Object... args) {
        requireNonNull(status, "status");
        requireNonNull(cause, "cause");
        requireNonNull(format, "format");
        requireNonNull(args, "args");

        return newResponse(status, cause, String.format(Locale.ENGLISH, format, args));
    }

    /**
     * Returns a newly created {@link HttpResponse} with the specified {@link HttpStatus}, {@code cause} and
     * {@code message}.
     */
    public static HttpResponse newResponse(HttpStatus status, Throwable cause, String message) {
        requireNonNull(status, "status");
        requireNonNull(cause, "cause");
        requireNonNull(message, "message");

        return newResponse0(status, cause, message);
    }

    private static HttpResponse newResponse0(HttpStatus status,
                                             @Nullable Throwable cause,
                                             @Nullable String message) {
        final ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (cause != null) {
            node.put("exception", cause.getClass().getName());
            if (message == null) {
                message = cause.getMessage();
            }
        }

        node.put("message", message != null ? message : status.reasonPhrase());

        // TODO(minwoox) refine the error message
        try {
            return HttpResponse.of(status, MediaType.JSON_UTF_8, Jackson.writeValueAsBytes(node));
        } catch (JsonProcessingException e) {
            // should not reach here
            throw new Error(e);
        }
    }

    /**
     * Ensures the specified {@link JsonNode} equals to {@link HttpApiUtil#unremoveRequest}.
     *
     * @throws IllegalArgumentException if the {@code node} does not equal to
     *                                  {@link HttpApiUtil#unremoveRequest}.
     */
    static void checkUnremoveArgument(JsonNode node) {
        checkArgument(unremoveRequest.equals(node),
                      "Unsupported JSON patch: " + node +
                      " (expected: " + unremoveRequest + ')');
    }

    /**
     * Ensures the specified {@code status} is valid.
     *
     * @throws IllegalArgumentException if the {@code status} is invalid.
     */
    static void checkStatusArgument(String status) {
        checkArgument("removed".equalsIgnoreCase(status),
                      "invalid status: " + status + " (expected: removed)");
    }

    /**
     * Throws the specified {@link Throwable} if it is not {@code null}. This is a special form to be used
     * for {@link CompletionStage#handle(BiFunction)}.
     */
    @SuppressWarnings("unused")
    static Void throwUnsafelyIfNonNull(@Nullable Object unused,
                                       @Nullable Throwable cause) {
        throwUnsafelyIfNonNull(cause);
        return null;
    }

    /**
     * Throws the specified {@link Throwable} if it is not {@code null}.
     */
    static void throwUnsafelyIfNonNull(@Nullable Throwable cause) {
        if (cause != null) {
            Exceptions.throwUnsafely(cause);
        }
    }

    /**
     * Returns a {@link BiFunction} for a {@link CompletionStage#handle(BiFunction)} which returns an object
     * if the specified {@link Throwable} is {@code null}. Otherwise, it throws the {@code cause}.
     */
    @SuppressWarnings("unchecked")
    static <T, U> BiFunction<? super T, Throwable, ? extends U> returnOrThrow(Supplier<? super U> supplier) {
        return (unused, cause) -> {
            throwUnsafelyIfNonNull(cause);
            return (U) supplier.get();
        };
    }

    /**
     * Returns a {@link BiFunction} for a {@link CompletionStage#handle(BiFunction)} which returns an object
     * applied by the specified {@code function} if the specified {@link Throwable} is {@code null}.
     * Otherwise, it throws the {@code cause}.
     */
    static <T, U> BiFunction<? super T, Throwable, ? extends U> returnOrThrow(
            Function<? super T, ? extends U> function) {
        return (result, cause) -> {
            throwUnsafelyIfNonNull(cause);
            return function.apply(result);
        };
    }

    private HttpApiUtil() {}
}
