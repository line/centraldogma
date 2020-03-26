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

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthFailureHandler;
import com.linecorp.centraldogma.common.AuthorizationException;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;

final class CentralDogmaAuthFailureHandler implements AuthFailureHandler {

    private static final Logger logger = LoggerFactory.getLogger(CentralDogmaAuthFailureHandler.class);

    private static final AuthorizationException AUTHORIZATION_EXCEPTION = new AuthorizationException("", false);

    @Override
    public HttpResponse authFailed(HttpService delegate,
                                   ServiceRequestContext ctx, HttpRequest req,
                                   @Nullable Throwable cause) throws Exception {
        if (cause != null) {
            if (!(cause instanceof ShuttingDownException)) {
                logger.warn("Unexpected exception during authorization:", cause);
            }
            return HttpApiUtil.newResponse(ctx, HttpStatus.INTERNAL_SERVER_ERROR, cause);
        }

        return HttpApiUtil.newResponse(ctx, HttpStatus.UNAUTHORIZED, AUTHORIZATION_EXCEPTION);
    }
}
