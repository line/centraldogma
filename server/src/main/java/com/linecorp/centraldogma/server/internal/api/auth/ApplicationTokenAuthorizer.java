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

import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.internal.admin.auth.AbstractAuthorizer;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;
import com.linecorp.centraldogma.server.metadata.ApplicationNotFoundException;
import com.linecorp.centraldogma.server.metadata.ApplicationRegistry;
import com.linecorp.centraldogma.server.metadata.Token;
import com.linecorp.centraldogma.server.metadata.UserWithApplication;

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
        if (!ApplicationRegistry.isValidSecret(accessToken)) {
            return completedFuture(false);
        }

        try {
            final Token appToken = tokenLookupFunc.apply(accessToken);
            if (appToken != null && appToken.isActive()) {
                final String appId = appToken.appId();
                ctx.logBuilder().authenticatedUser("app/" + appId + "/token");
                final UserWithApplication user = new UserWithApplication(appToken);
                AuthUtil.setCurrentUser(ctx, user);
                HttpApiUtil.setVerboseResponses(ctx, user);
                return UnmodifiableFuture.completedFuture(true);
            }
            return UnmodifiableFuture.completedFuture(false);
        } catch (Throwable cause) {
            cause = Exceptions.peel(cause);
            if (cause instanceof IllegalArgumentException ||
                cause instanceof ApplicationNotFoundException) {
                // Do not log the cause.
                logger.debug("Failed to authorize an application token: token={}, addr={}",
                             maskToken(accessToken), ctx.clientAddress());
            } else {
                logger.warn("Failed to authorize an application token: token={}, addr={}",
                             maskToken(accessToken), ctx.clientAddress(), cause);
            }
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
