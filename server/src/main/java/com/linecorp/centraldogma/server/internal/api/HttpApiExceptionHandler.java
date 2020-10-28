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
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.newResponse;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.EntryNoContentException;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.QueryExecutionException;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.common.TooManyRequestsException;
import com.linecorp.centraldogma.server.internal.admin.service.TokenNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.RequestAlreadyTimedOutException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryMetadataException;

/**
 * A default {@link ExceptionHandlerFunction} of HTTP API.
 */
public final class HttpApiExceptionHandler implements ExceptionHandlerFunction {

    /**
     * A map of exception handler functions for well known exceptions.
     */
    private static final Map<Class<?>, ExceptionHandlerFunction> exceptionHandlers;

    static {
        final ImmutableMap.Builder<Class<?>,
                ExceptionHandlerFunction> builder = ImmutableMap.builder();

        builder.put(ChangeConflictException.class,
                    (ctx, req, cause) -> newResponse(ctx, HttpStatus.CONFLICT, cause,
                                                     "The given changeset or revision has a conflict."))
               .put(EntryNotFoundException.class,
                    (ctx, req, cause) -> newResponse(ctx, HttpStatus.NOT_FOUND, cause,
                                                     "Entry '%s' does not exist.", cause.getMessage()))
               .put(EntryNoContentException.class,
                    (ctx, req, cause) -> HttpResponse.of(HttpStatus.NO_CONTENT))
               .put(ProjectExistsException.class,
                    (ctx, req, cause) -> newResponse(ctx, HttpStatus.CONFLICT, cause,
                                                     "Project '%s' exists already.", cause.getMessage()))
               .put(ProjectNotFoundException.class,
                    (ctx, req, cause) -> newResponse(ctx, HttpStatus.NOT_FOUND, cause,
                                                     "Project '%s' does not exist.", cause.getMessage()))
               .put(RedundantChangeException.class,
                    (ctx, req, cause) -> newResponse(ctx, HttpStatus.CONFLICT, cause,
                                                     "The given changeset does not change anything."))
               .put(RepositoryExistsException.class,
                    (ctx, req, cause) -> newResponse(ctx, HttpStatus.CONFLICT, cause,
                                                     "Repository '%s' exists already.", cause.getMessage()))
               .put(RepositoryMetadataException.class,
                    (ctx, req, cause) -> newResponse(ctx, HttpStatus.INTERNAL_SERVER_ERROR, cause))
               .put(RepositoryNotFoundException.class,
                    (ctx, req, cause) -> newResponse(ctx, HttpStatus.NOT_FOUND, cause,
                                                     "Repository '%s' does not exist.", cause.getMessage()))
               .put(RevisionNotFoundException.class,
                    (ctx, req, cause) -> newResponse(ctx, HttpStatus.NOT_FOUND, cause,
                                                     "Revision %s does not exist.", cause.getMessage()))
               .put(TokenNotFoundException.class,
                    (ctx, req, cause) -> newResponse(ctx, HttpStatus.NOT_FOUND, cause,
                                                     "Token '%s' does not exist.", cause.getMessage()))
               .put(QueryExecutionException.class,
                    (ctx, req, cause) -> newResponse(ctx, HttpStatus.BAD_REQUEST, cause))
               .put(TooManyRequestsException.class,
                    (ctx, req, cause) -> {
                        final TooManyRequestsException cast = (TooManyRequestsException) cause;
                        final Object type = firstNonNull(cast.type(), "requests");
                        return newResponse(ctx, HttpStatus.TOO_MANY_REQUESTS, cast,
                                           "Too many %s are sent to %s", type, cause.getMessage());
                    });

        exceptionHandlers = builder.build();
    }

    @Override
    public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {

        if (cause instanceof HttpStatusException ||
            cause instanceof HttpResponseException) {
            return ExceptionHandlerFunction.fallthrough();
        }

        // Use precomputed map if the cause is instance of CentralDogmaException to access in a faster way.
        final ExceptionHandlerFunction func = exceptionHandlers.get(cause.getClass());
        if (func != null) {
            return func.handleException(ctx, req, cause);
        }

        if (cause instanceof IllegalArgumentException) {
            return newResponse(ctx, HttpStatus.BAD_REQUEST, cause);
        }

        if (cause instanceof RequestAlreadyTimedOutException) {
            return newResponse(ctx, HttpStatus.SERVICE_UNAVAILABLE, cause);
        }

        return newResponse(ctx, HttpStatus.INTERNAL_SERVER_ERROR, cause);
    }
}
