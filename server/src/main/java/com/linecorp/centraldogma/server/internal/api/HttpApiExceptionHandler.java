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

import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.newResponseWithErrorMessage;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.centraldogma.server.internal.admin.service.TokenNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.StorageException;
import com.linecorp.centraldogma.server.internal.storage.StorageExistsException;
import com.linecorp.centraldogma.server.internal.storage.StorageNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectExistsException;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.repository.ChangeConflictException;
import com.linecorp.centraldogma.server.internal.storage.repository.EntryNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.repository.RedundantChangeException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryExistsException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryMetadataException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.repository.RevisionExistsException;
import com.linecorp.centraldogma.server.internal.storage.repository.RevisionNotFoundException;

/**
 * A default {@link ExceptionHandlerFunction} of HTTP API.
 */
public final class HttpApiExceptionHandler implements ExceptionHandlerFunction {

    /**
     * A map of exception handler functions for the classes which inherit {@link StorageException}.
     */
    private final Map<Class<?>, ExceptionHandlerFunction> storageExceptionHandlers =
            ImmutableMap.<Class<?>, ExceptionHandlerFunction>builder()
                    .put(ChangeConflictException.class, HttpApiExceptionHandler::handleConflict)
                    .put(EntryNotFoundException.class, HttpApiExceptionHandler::handleNotFound)
                    .put(ProjectExistsException.class, HttpApiExceptionHandler::handleExists)
                    .put(ProjectNotFoundException.class, HttpApiExceptionHandler::handleNotFound)
                    .put(RedundantChangeException.class, HttpApiExceptionHandler::handleRedundantChange)
                    .put(RepositoryExistsException.class, HttpApiExceptionHandler::handleExists)
                    .put(RepositoryMetadataException.class, HttpApiExceptionHandler::fallthrough)
                    .put(RepositoryNotFoundException.class, HttpApiExceptionHandler::handleNotFound)
                    .put(RevisionExistsException.class, HttpApiExceptionHandler::fallthrough)
                    .put(RevisionNotFoundException.class, HttpApiExceptionHandler::handleNotFound)
                    .put(StorageExistsException.class, HttpApiExceptionHandler::handleExists)
                    .put(StorageNotFoundException.class, HttpApiExceptionHandler::handleNotFound)
                    .put(TokenNotFoundException.class, HttpApiExceptionHandler::handleNotFound)
                    .build();

    @Override
    public HttpResponse handleException(RequestContext ctx, HttpRequest req, Throwable cause) {

        if (cause instanceof IllegalArgumentException) {
            if (cause.getMessage() != null) {
                return newResponseWithErrorMessage(HttpStatus.BAD_REQUEST, cause.getMessage());
            }
            return HttpResponse.of(HttpStatus.BAD_REQUEST);
        }

        if (cause instanceof StorageException) {
            // Use precomputed map if the cause is instance of StorageException to access in a faster way.
            final ExceptionHandlerFunction func = storageExceptionHandlers.get(cause.getClass());
            if (func != null) {
                return func.handleException(ctx, req, cause);
            }
        }

        return ExceptionHandlerFunction.fallthrough();
    }

    @SuppressWarnings("unused")
    static HttpResponse handleExists(RequestContext ctx, HttpRequest req, Throwable cause) {
        return newResponseWithErrorMessage(HttpStatus.CONFLICT,
                                           cause.getMessage() + " already exists.");
    }

    @SuppressWarnings("unused")
    static HttpResponse handleNotFound(RequestContext ctx, HttpRequest req, Throwable cause) {
        return newResponseWithErrorMessage(HttpStatus.NOT_FOUND,
                                           cause.getMessage() + " does not exist.");
    }

    @SuppressWarnings("unused")
    static HttpResponse handleRedundantChange(RequestContext ctx, HttpRequest req, Throwable cause) {
        return newResponseWithErrorMessage(HttpStatus.BAD_REQUEST, "changes did not change anything.");
    }

    @SuppressWarnings("unused")
    static HttpResponse handleConflict(RequestContext ctx, HttpRequest req, Throwable cause) {
        return HttpResponse.of(HttpStatus.CONFLICT);
    }

    @SuppressWarnings("unused")
    static HttpResponse fallthrough(RequestContext ctx, HttpRequest req, Throwable cause) {
        return ExceptionHandlerFunction.fallthrough();
    }
}
