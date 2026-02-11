/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.centraldogma.server;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.AnnotatedService;
import com.linecorp.armeria.server.auth.AuthFailureHandler;
import com.linecorp.centraldogma.common.AuthorizationException;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.server.internal.api.GitHttpService;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;

final class CentralDogmaAuthFailureHandler implements AuthFailureHandler {

    private static final Logger logger = LoggerFactory.getLogger(CentralDogmaAuthFailureHandler.class);

    private static final AuthorizationException AUTHORIZATION_EXCEPTION = new AuthorizationException("", false);
    private static final ResponseHeaders UNAUTHORIZED_HEADERS =
            ResponseHeaders.builder(HttpStatus.UNAUTHORIZED)
                           .add(HttpHeaderNames.WWW_AUTHENTICATE,
                                "Bearer realm=\"Central Dogma\", charset=\"UTF-8\"")
                           .add(HttpHeaderNames.WWW_AUTHENTICATE,
                                "Basic realm=\"Central Dogma\", charset=\"UTF-8\"")
                           .build();

    @Override
    public HttpResponse authFailed(HttpService delegate,
                                   ServiceRequestContext ctx, HttpRequest req,
                                   @Nullable Throwable cause) throws Exception {
        logger.trace("Authorization failed. delegate: {}, ctx: {}", delegate, ctx);
        if (cause != null) {
            if (!(cause instanceof ShuttingDownException)) {
                logger.warn("Unexpected exception during authorization:", cause);
            }
            return HttpApiUtil.newResponse(ctx, HttpStatus.INTERNAL_SERVER_ERROR, cause);
        }

        final AnnotatedService annotatedService = ctx.config().service().as(AnnotatedService.class);
        if (annotatedService != null && annotatedService.serviceClass() == GitHttpService.class) {
            // Use 401 with WWW-Authenticate headers for Git over HTTP(S) clients.
            return HttpApiUtil.newResponse(ctx, UNAUTHORIZED_HEADERS, AUTHORIZATION_EXCEPTION);
        }
        return HttpApiUtil.newResponse(ctx, HttpStatus.UNAUTHORIZED, AUTHORIZATION_EXCEPTION);
    }
}
