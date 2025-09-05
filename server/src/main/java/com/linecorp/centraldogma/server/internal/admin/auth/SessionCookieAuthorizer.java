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

package com.linecorp.centraldogma.server.internal.admin.auth;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.X_CSRF_TOKEN;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.getSessionIdFromCookie;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.sessionCookieName;
import static com.linecorp.centraldogma.server.metadata.User.LEVEL_SYSTEM_ADMIN;
import static com.linecorp.centraldogma.server.metadata.User.LEVEL_USER;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthorizationStatus;
import com.linecorp.armeria.server.auth.Authorizer;
import com.linecorp.centraldogma.server.auth.SessionManager;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;
import com.linecorp.centraldogma.server.metadata.User;

/**
 * A decorator to check whether the request holds a valid token. If it holds a valid token, this
 * decorator would find a session belonging to the token and attach it to the service context attributes.
 */
public class SessionCookieAuthorizer implements Authorizer<HttpRequest> {

    private static final Logger logger = LoggerFactory.getLogger(SessionCookieAuthorizer.class);

    private final SessionManager sessionManager;
    private final String sessionCookieName;
    private final Set<String> systemAdministrators;

    public SessionCookieAuthorizer(SessionManager sessionManager, boolean tlsEnabled,
                                   Set<String> systemAdministrators) {
        this.sessionManager = requireNonNull(sessionManager, "sessionManager");
        sessionCookieName = sessionCookieName(tlsEnabled);
        this.systemAdministrators = requireNonNull(systemAdministrators, "systemAdministrators");
    }

    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest req) {
        // This isn't called.
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<AuthorizationStatus> authorizeAndSupplyHandlers(
            ServiceRequestContext ctx, @Nullable HttpRequest req) {
        if (req == null) {
            return UnmodifiableFuture.completedFuture(AuthorizationStatus.of(false));
        }

        final String sessionId = getSessionIdFromCookie(ctx, sessionCookieName);
        if (sessionId == null) {
            return UnmodifiableFuture.completedFuture(AuthorizationStatus.of(false));
        }
        return sessionManager.get(sessionId).thenApply(session -> {
            if (session == null) {
                logger.trace("Session not found (or expired): {}, ctx={}", sessionId, ctx);
                return AuthorizationStatus.of(false);
            }
            // Check the token when the method is not safe:
            // https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#javascript-automatically-including-csrf-tokens-as-an-ajax-request-header
            if (!isSafeMethod(req)) {
                final String csrfToken = req.headers().get(X_CSRF_TOKEN);
                if (isNullOrEmpty(csrfToken) || !csrfToken.equals(session.csrfToken())) {
                    logger.trace("CSRF token mismatch: csrfToken={}, expectedCsrfToken={}, ctx={}",
                                 csrfToken, session.csrfToken(), ctx);
                    return AuthorizationStatus.ofFailure((delegate, ctx1, req1, cause) -> {
                        return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.PLAIN_TEXT_UTF_8,
                                               "Invalid CSRF token");
                    });
                }
            }

            final String username = session.username();
            final List<String> roles =
                    systemAdministrators.contains(username) ? LEVEL_SYSTEM_ADMIN
                                                            : LEVEL_USER;
            final User user = new User(username, roles);
            ctx.logBuilder().authenticatedUser("user/" + username);
            AuthUtil.setCurrentUser(ctx, user);
            HttpApiUtil.setVerboseResponses(ctx, user);
            return AuthorizationStatus.of(true);
        });
    }

    private static boolean isSafeMethod(HttpRequest req) {
        switch (req.method()) {
            case GET:
            case HEAD:
            case OPTIONS:
                return true;
            default:
                return false;
        }
    }
}
