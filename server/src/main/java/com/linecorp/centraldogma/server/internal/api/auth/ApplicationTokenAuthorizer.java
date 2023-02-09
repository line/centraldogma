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

package com.linecorp.centraldogma.server.internal.api.auth;

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.auth.OAuth2Token;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import com.linecorp.armeria.server.auth.Authorizer;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.metadata.Token;
import com.linecorp.centraldogma.server.metadata.Tokens;
import com.linecorp.centraldogma.server.metadata.UserWithToken;

/**
 * A decorator which finds an application token from a request and validates it.
 */
public class ApplicationTokenAuthorizer implements Authorizer<HttpRequest> {

    private static final Logger logger = LoggerFactory.getLogger(
            ApplicationTokenAuthorizer.class);

    private final Function<String, CompletionStage<Token>> tokenLookupFunc;

    public ApplicationTokenAuthorizer(Function<String, CompletionStage<Token>> tokenLookupFunc) {
        this.tokenLookupFunc = requireNonNull(tokenLookupFunc, "tokenLookupFunc");
    }

    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest data) {
        final OAuth2Token token = AuthTokenExtractors.oAuth2().apply(data.headers());
        if (token == null || !Tokens.isValidSecret(token.accessToken())) {
            return completedFuture(false);
        }

        final CompletableFuture<Boolean> res = new CompletableFuture<>();
        tokenLookupFunc.apply(token.accessToken())
                       .thenAccept(appToken -> {
                           if (appToken != null && appToken.isActive()) {
                               final String appId = appToken.appId();
                               final StringBuilder login = new StringBuilder(appId);
                               final SocketAddress ra = ctx.remoteAddress();
                               if (ra instanceof InetSocketAddress) {
                                   login.append('@').append(((InetSocketAddress) ra).getHostString());
                               }
                               ctx.logBuilder().authenticatedUser(appId);
                               AuthUtil.setCurrentUser(
                                       ctx, new UserWithToken(login.toString(), appToken));
                               res.complete(true);
                           } else {
                               res.complete(false);
                           }
                       })
                       // Should be authorized by the next authorizer.
                       .exceptionally(voidFunction(cause -> {
                           cause = Exceptions.peel(cause);
                           if (!(cause instanceof IllegalArgumentException)) {
                               logger.warn("Application token authorization failed: token={}, addr={}",
                                           token.accessToken(), ctx.clientAddress(), cause);
                           }
                           res.complete(false);
                       }));

        return res;
    }
}
