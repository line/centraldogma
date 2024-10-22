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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.nullToEmpty;
import static java.util.Objects.requireNonNull;

import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.metadata.User;

import io.netty.util.AttributeKey;

/**
 * A utility class which provides common functions for HTTP API.
 */
//TODO(minwoox) change this class to package-local when the admin API is integrated with HTTP API
public final class HttpApiUtil {

    private static final AttributeKey<Boolean> VERBOSE_RESPONSES =
            AttributeKey.valueOf(HttpApiUtil.class, "VERBOSE_RESPONSES");

    private static final Logger logger = LoggerFactory.getLogger(HttpApiUtil.class);
    private static final String ERROR_MESSAGE_FORMAT = "{} Returning a {} response: {}";

    static final JsonNode unremoveRequest = Jackson.valueToTree(
            ImmutableList.of(
                    ImmutableMap.of("op", "replace",
                                    "path", "/status",
                                    "value", "active")));

    /**
     * Throws a newly created {@link HttpResponseException} with the specified {@link HttpStatus} and
     * {@code message}.
     */
    public static <T> T throwResponse(ServiceRequestContext ctx, HttpStatus status, String message) {
        throw HttpResponseException.of(newResponse(ctx, status, message));
    }

    /**
     * Throws a newly created {@link HttpResponseException} with the specified {@link HttpStatus} and
     * the formatted message.
     */
    public static <T> T throwResponse(ServiceRequestContext ctx, HttpStatus status, String format,
                                      Object... args) {
        throw HttpResponseException.of(newResponse(ctx, status, format, args));
    }

    /**
     * Throws a newly created {@link HttpResponseException} with the specified {@link HttpStatus},
     * {@code cause} and {@code message}.
     */
    public static <T> T throwResponse(ServiceRequestContext ctx, HttpStatus status, Throwable cause,
                                      String message) {
        throw HttpResponseException.of(newResponse(ctx, status, cause, message));
    }

    /**
     * Throws a newly created {@link HttpResponseException} with the specified {@link HttpStatus},
     * {@code cause} and the formatted message.
     */
    public static <T> T throwResponse(ServiceRequestContext ctx, HttpStatus status, Throwable cause,
                                      String format, Object... args) {
        throw HttpResponseException.of(newResponse(ctx, status, cause, format, args));
    }

    /**
     * Returns a newly created {@link HttpResponse} with the specified {@link HttpStatus} and the formatted
     * message.
     */
    public static HttpResponse newResponse(ServiceRequestContext ctx, HttpStatus status,
                                           String format, Object... args) {
        requireNonNull(ctx, "ctx");
        requireNonNull(status, "status");
        requireNonNull(format, "format");
        requireNonNull(args, "args");
        return newResponse(ctx, status, String.format(Locale.ENGLISH, format, args));
    }

    /**
     * Returns a newly created {@link HttpResponse} with the specified {@link HttpStatus} and {@code message}.
     */
    public static HttpResponse newResponse(ServiceRequestContext ctx, HttpStatus status, String message) {
        requireNonNull(ctx, "ctx");
        requireNonNull(status, "status");
        requireNonNull(message, "message");
        return newResponse0(ctx, status, null, message);
    }

    /**
     * Returns a newly created {@link HttpResponse} with the specified {@link HttpStatus} and {@code cause}.
     */
    public static HttpResponse newResponse(ServiceRequestContext ctx, HttpStatus status, Throwable cause) {
        requireNonNull(ctx, "ctx");
        requireNonNull(status, "status");
        requireNonNull(cause, "cause");
        return newResponse0(ctx, status, cause, null);
    }

    /**
     * Returns a newly created {@link HttpResponse} with the specified {@link HttpStatus}, {@code cause} and
     * the formatted message.
     */
    public static HttpResponse newResponse(ServiceRequestContext ctx, HttpStatus status, Throwable cause,
                                           String format, Object... args) {
        requireNonNull(ctx, "ctx");
        requireNonNull(status, "status");
        requireNonNull(cause, "cause");
        requireNonNull(format, "format");
        requireNonNull(args, "args");

        return newResponse(ctx, status, cause, String.format(Locale.ENGLISH, format, args));
    }

    /**
     * Returns a newly created {@link HttpResponse} with the specified {@link HttpStatus}, {@code cause} and
     * {@code message}.
     */
    public static HttpResponse newResponse(ServiceRequestContext ctx, HttpStatus status,
                                           Throwable cause, String message) {
        requireNonNull(ctx, "ctx");
        requireNonNull(status, "status");
        requireNonNull(cause, "cause");
        requireNonNull(message, "message");

        return newResponse0(ctx, status, cause, message);
    }

    private static HttpResponse newResponse0(ServiceRequestContext ctx, HttpStatus status,
                                             @Nullable Throwable cause, @Nullable String message) {
        checkArgument(!status.isContentAlwaysEmpty(),
                      "status: %s (expected: a status with non-empty content)", status);

        final ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (cause != null) {
            cause = Exceptions.peel(cause);
            node.put("exception", cause.getClass().getName());
            if (message == null) {
                message = cause.getMessage();
            }
        }

        final String m = nullToEmpty(message);
        node.put("message", m);

        if (cause != null && isVerboseResponse(ctx)) {
            node.put("detail", Exceptions.traceText(cause));
        }

        final LogLevel logLevel;
        switch (status.codeClass()) {
            case SERVER_ERROR:
                if (cause != null) {
                    if (!(Exceptions.isStreamCancelling(cause) ||
                          cause instanceof ShuttingDownException)) {
                        logLevel = LogLevel.WARN;
                    } else {
                        logLevel = null;
                    }
                } else {
                    logLevel = LogLevel.WARN;
                }
                break;
            case CLIENT_ERROR:
                logLevel = LogLevel.DEBUG;
                break;
            case UNKNOWN:
                logLevel = LogLevel.WARN;
                break;
            default:
                logLevel = null;
        }

        // TODO(trustin): Use LogLevel.OFF instead of null and logLevel.log()
        //                once we add LogLevel.OFF and LogLevel.log() with more args.
        if (logLevel != null) {
            if (logLevel == LogLevel.WARN) {
                if (cause != null) {
                    logger.warn(ERROR_MESSAGE_FORMAT, ctx, status, m, cause);
                } else {
                    logger.warn(ERROR_MESSAGE_FORMAT, ctx, status, m);
                }
            } else {
                if (cause != null) {
                    logger.debug(ERROR_MESSAGE_FORMAT, ctx, status, m, cause);
                } else {
                    logger.debug(ERROR_MESSAGE_FORMAT, ctx, status, m);
                }
            }
        }

        // TODO(hyangtack) Need to introduce a new field such as 'stackTrace' in order to return
        //                 the stack trace of the cause to the trusted client.
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
    public static void throwUnsafelyIfNonNull(@Nullable Throwable cause) {
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

    public static void setVerboseResponses(ServiceRequestContext ctx, User user) {
        ctx.setAttr(VERBOSE_RESPONSES, Flags.verboseResponses() || user.isAdmin());
    }

    private static boolean isVerboseResponse(ServiceRequestContext ctx) {
        final Boolean verboseResponses = ctx.attr(VERBOSE_RESPONSES);
        return firstNonNull(verboseResponses, false);
    }

    private HttpApiUtil() {}
}
