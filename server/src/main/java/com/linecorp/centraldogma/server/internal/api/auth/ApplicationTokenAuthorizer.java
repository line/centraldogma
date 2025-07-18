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

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.internal.admin.auth.AbstractAuthorizer;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;
import com.linecorp.centraldogma.server.metadata.Token;
import com.linecorp.centraldogma.server.metadata.TokenNotFoundException;
import com.linecorp.centraldogma.server.metadata.Tokens;
import com.linecorp.centraldogma.server.metadata.UserWithToken;

/**
 * A decorator which finds an application token from a request and validates it.
 */
public class ApplicationTokenAuthorizer extends AbstractAuthorizer {

    private static final Logger logger = LoggerFactory.getLogger(
            ApplicationTokenAuthorizer.class);

    private final Function<String, Token> tokenLookupFunc;

    public ApplicationTokenAuthorizer(Function<String, Token> tokenLookupFunc) {
        this.tokenLookupFunc = requireNonNull(tokenLookupFunc, "tokenLookupFunc");
    }

    @Override
    protected CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest req,
                                                 String accessToken) {
        if (!Tokens.isValidSecret(accessToken)) {
            return completedFuture(false);
        }

        try {
            final Token appToken = tokenLookupFunc.apply(accessToken);
            if (appToken != null && appToken.isActive()) {
                final String appId = appToken.appId();
                final StringBuilder login = new StringBuilder(appId);
                final SocketAddress ra = ctx.remoteAddress();
                if (ra instanceof InetSocketAddress) {
                    login.append('@').append(((InetSocketAddress) ra).getHostString());
                }
                ctx.logBuilder().authenticatedUser("app/" + appId);
                final UserWithToken user = new UserWithToken(login.toString(), appToken);
                AuthUtil.setCurrentUser(ctx, user);
                HttpApiUtil.setVerboseResponses(ctx, user);
                return UnmodifiableFuture.completedFuture(true);
            }
            return UnmodifiableFuture.completedFuture(false);
        } catch (Throwable cause) {
            cause = Exceptions.peel(cause);
            final LogLevel level;
            if (cause instanceof IllegalArgumentException ||
                cause instanceof TokenNotFoundException) {
                level = LogLevel.DEBUG;
            } else {
                level = LogLevel.WARN;
            }
            level.log(logger, "Failed to authorize an application token: token={}, addr={}",
                      maskToken(accessToken), ctx.clientAddress(), cause);
            return UnmodifiableFuture.completedFuture(false);
        }
    }

    private static String maskToken(String token) {
        if (!token.startsWith("appToken-")) {
            // Unknown token type
            return token;
        }

        // Token format: appToken-aaaa-bbbb-cccc-dddd-eeee
        final int lastDash = token.lastIndexOf('-');
        if (lastDash == -1) {
            // Invalid token format
            return token;
        }
        // Redact the last part of the token
        return token.substring(0, lastDash) + "-<redacted>";
    }
}
