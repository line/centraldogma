/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.admin.auth;

import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.auth.OAuth2Token;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import com.linecorp.armeria.server.auth.Authorizer;

import io.netty.util.AttributeKey;

public abstract class AbstractAuthorizer implements Authorizer<HttpRequest> {

    private static final AttributeKey<String> TOKEN_KEY =
            AttributeKey.valueOf(AbstractAuthorizer.class, "TOKEN_KEY");

    private static final String BASIC_USERNAME = "dogma";

    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest req) {
        final String token = extractToken(ctx, req);
        if (token == null) {
            return UnmodifiableFuture.completedFuture(false);
        }

        return authorize(ctx, req, token);
    }

    protected abstract CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest req,
                                                          String accessToken);

    @Nullable
    private static String extractToken(ServiceRequestContext ctx, HttpRequest req) {
        String token = ctx.attr(TOKEN_KEY);
        if (token != null) {
            return token;
        }

        final RequestHeaders headers = req.headers();
        final String authorization = headers.get(HttpHeaderNames.AUTHORIZATION);
        if (authorization == null) {
            return null;
        }

        // Check the scheme of the authorization header to avoid unnecessary warning logs.
        if (authorization.startsWith("Bearer")) {
            final OAuth2Token oAuth2Token = AuthTokenExtractors.oAuth2().apply(headers);
            if (oAuth2Token != null) {
                token = oAuth2Token.accessToken();
            }
        } else if (authorization.startsWith("Basic")) {
            final BasicToken basicToken = AuthTokenExtractors.basic().apply(headers);
            if (basicToken != null) {
                // Allow only the 'dogma' username for basic authentication.
                if (BASIC_USERNAME.equals(basicToken.username())) {
                    token = basicToken.password();
                }
            }
        }

        if (token != null) {
            ctx.setAttr(TOKEN_KEY, token);
        }
        return token;
    }
}
