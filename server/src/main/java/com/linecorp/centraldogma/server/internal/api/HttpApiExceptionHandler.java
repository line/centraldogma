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

import static com.linecorp.centraldogma.server.internal.api.ContentServiceV1.IS_WATCH_REQUEST;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.newAggregatedResponse;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.newResponse;

import java.util.Map;
import java.util.function.BiFunction;

import javax.annotation.Nonnull;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.ServerErrorHandler;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.centraldogma.common.ApiRequestTimeoutException;
import com.linecorp.centraldogma.common.AuthorizationException;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.EntryNoContentException;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.InvalidPushException;
import com.linecorp.centraldogma.common.LockAcquireTimeoutException;
import com.linecorp.centraldogma.common.MirrorAccessException;
import com.linecorp.centraldogma.common.MirrorException;
import com.linecorp.centraldogma.common.PermissionException;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.QueryExecutionException;
import com.linecorp.centraldogma.common.ReadOnlyException;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.common.TemplateProcessingException;
import com.linecorp.centraldogma.common.TextPatchConflictException;
import com.linecorp.centraldogma.common.jsonpatch.JsonPatchConflictException;
import com.linecorp.centraldogma.server.internal.storage.RequestAlreadyTimedOutException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryMetadataException;
import com.linecorp.centraldogma.server.metadata.MemberNotFoundException;
import com.linecorp.centraldogma.server.metadata.TokenNotFoundException;

/**
 * A default {@link ExceptionHandlerFunction} of HTTP API.
 */
public final class HttpApiExceptionHandler implements ServerErrorHandler {

    /**
     * A map of exception handler functions for well known exceptions.
     */
    private static final Map<Class<?>, BiFunction<ServiceRequestContext, Throwable, HttpResponse>>
            exceptionHandlers;

    static {
        final ImmutableMap.Builder<Class<?>,
                BiFunction<ServiceRequestContext, Throwable, HttpResponse>> builder = ImmutableMap.builder();

        builder.put(ChangeConflictException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.CONFLICT, cause,
                                                "The given changeset or revision has a conflict."))
               .put(EntryNotFoundException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.NOT_FOUND, cause,
                                                "Entry '%s' does not exist.", cause.getMessage()))
               .put(EntryNoContentException.class,
                    (ctx, cause) -> HttpResponse.of(HttpStatus.NO_CONTENT))
               .put(ProjectExistsException.class,
                    (ctx, cause) -> newResponse(HttpStatus.CONFLICT,
                                                cause.getClass().getName(), cause.getMessage()))
               .put(ProjectNotFoundException.class,
                    (ctx, cause) -> newResponse(HttpStatus.NOT_FOUND, cause.getClass().getName(),
                                                cause.getMessage()))
               .put(RedundantChangeException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.CONFLICT, cause,
                                                "The given changeset does not change anything."))
               .put(RepositoryExistsException.class,
                    (ctx, cause) -> newResponse(HttpStatus.CONFLICT, cause.getClass().getName(),
                                                cause.getMessage()))
               .put(JsonPatchConflictException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.CONFLICT, cause))
               .put(TextPatchConflictException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.CONFLICT, cause))
               .put(RepositoryMetadataException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.INTERNAL_SERVER_ERROR, cause))
               .put(RepositoryNotFoundException.class,
                    (ctx, cause) -> newResponse(HttpStatus.NOT_FOUND, cause.getClass().getName(),
                                                cause.getMessage()))
               .put(RevisionNotFoundException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.NOT_FOUND, cause,
                                                "Revision %s does not exist.", cause.getMessage()))
               .put(TokenNotFoundException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.NOT_FOUND, cause, cause.getMessage()))
               .put(MemberNotFoundException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.NOT_FOUND, cause, cause.getMessage()))
               .put(QueryExecutionException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.BAD_REQUEST, cause))
               .put(UnsupportedOperationException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.BAD_REQUEST, cause))
               .put(InvalidPushException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.BAD_REQUEST, cause))
               .put(ReadOnlyException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.SERVICE_UNAVAILABLE, cause))
               .put(MirrorException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.INTERNAL_SERVER_ERROR, cause))
               .put(MirrorAccessException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.FORBIDDEN, cause))
               .put(AuthorizationException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.UNAUTHORIZED, cause))
               .put(PermissionException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.FORBIDDEN, cause))
               .put(LockAcquireTimeoutException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.SERVICE_UNAVAILABLE, cause))
               .put(TemplateProcessingException.class,
                    (ctx, cause) -> newResponse(ctx, HttpStatus.INTERNAL_SERVER_ERROR, cause));

        exceptionHandlers = builder.build();
    }

    @Override
    public HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
        final Throwable peeledCause = Exceptions.peel(cause);

        if (peeledCause instanceof HttpStatusException ||
            peeledCause instanceof HttpResponseException) {
            return null;
        }

        // Use precomputed map if the cause is instance of CentralDogmaException to access in a faster way.
        final BiFunction<ServiceRequestContext, Throwable, HttpResponse> func =
                exceptionHandlers.get(peeledCause.getClass());
        if (func != null) {
            ctx.setShouldReportUnloggedExceptions(false);
            return func.apply(ctx, peeledCause);
        }

        if (peeledCause instanceof ShuttingDownException) {
            if (Boolean.TRUE.equals(ctx.attr(IS_WATCH_REQUEST))) {
                ctx.setShouldReportUnloggedExceptions(false);
                // Use the same status code as handleWatchFailure() in ContentServiceV1.
                return HttpResponse.of(HttpStatus.NOT_MODIFIED);
            }
        }

        if (peeledCause instanceof IllegalArgumentException) {
            ctx.setShouldReportUnloggedExceptions(false);
            return newResponse(ctx, HttpStatus.BAD_REQUEST, peeledCause);
        }

        if (peeledCause instanceof RequestAlreadyTimedOutException) {
            ctx.setShouldReportUnloggedExceptions(false);
            return newResponse(ctx, HttpStatus.SERVICE_UNAVAILABLE, peeledCause);
        }

        if (peeledCause instanceof RequestTimeoutException) {
            ctx.setShouldReportUnloggedExceptions(false);
            return newResponse(ctx, HttpStatus.SERVICE_UNAVAILABLE,
                               new ApiRequestTimeoutException("Request timed out", peeledCause));
        }

        return newResponse(ctx, HttpStatus.INTERNAL_SERVER_ERROR, peeledCause);
    }

    @Nonnull
    @Override
    public AggregatedHttpResponse renderStatus(@Nullable ServiceRequestContext ctx,
                                               ServiceConfig config, @Nullable RequestHeaders headers,
                                               HttpStatus status, @Nullable String description,
                                               @Nullable Throwable cause) {
        return newAggregatedResponse(ctx, status, cause, description);
    }
}
