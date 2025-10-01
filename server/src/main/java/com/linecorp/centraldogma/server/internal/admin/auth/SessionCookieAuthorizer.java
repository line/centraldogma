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

import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.validateCsrfToken;
import static com.linecorp.centraldogma.server.metadata.User.LEVEL_SYSTEM_ADMIN;
import static com.linecorp.centraldogma.server.metadata.User.LEVEL_USER;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthorizationStatus;
import com.linecorp.armeria.server.auth.Authorizer;
import com.linecorp.centraldogma.server.auth.SessionManager;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionCookieHandler.SessionInfo;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;

/**
 * A decorator to check whether the request holds a valid token. If it holds a valid token, this
 * decorator would find a session belonging to the token and attach it to the service context attributes.
 */
public class SessionCookieAuthorizer implements Authorizer<HttpRequest> {

    private static final Logger logger = LoggerFactory.getLogger(SessionCookieAuthorizer.class);

    private final SessionManager sessionManager;
    private final Set<String> systemAdministrators;
    private final SessionCookieHandler sessionCookieHandler;

    public SessionCookieAuthorizer(SessionManager sessionManager,
                                   Supplier<Boolean> sessionPropagatorWritableChecker,
                                   boolean tlsEnabled,
                                   EncryptionStorageManager encryptionStorageManager,
                                   Set<String> systemAdministrators) {
        this.sessionManager = requireNonNull(sessionManager, "sessionManager");
        requireNonNull(encryptionStorageManager, "encryptionStorageManager");
        sessionCookieHandler = new SessionCookieHandler(
                sessionPropagatorWritableChecker, tlsEnabled, encryptionStorageManager);
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

        final SessionInfo sessionInfo = sessionCookieHandler.getSessionInfo(ctx);
        if (sessionInfo == null) {
            return UnmodifiableFuture.completedFuture(AuthorizationStatus.of(false));
        }

        final String sessionId = sessionInfo.sessionId();
        if (sessionId == null)  {
            final String username = sessionInfo.username();
            final String csrfTokenFromSignedJwt = sessionInfo.csrfTokenFromSignedJwt();
            assert username != null;
            assert csrfTokenFromSignedJwt != null;
            if (!validateCsrfToken(ctx, req, csrfTokenFromSignedJwt)) {
                return UnmodifiableFuture.completedFuture(invalidCsrfToken());
            }
            setCurrentUser(ctx, username);
            return UnmodifiableFuture.completedFuture(AuthorizationStatus.of(true));
        }

        return sessionManager.get(sessionId).thenApply(session -> {
            if (session == null) {
                logger.trace("Session not found (or expired), ctx={}", ctx);
                return AuthorizationStatus.of(false);
            }

            if (!validateCsrfToken(ctx, req, session.csrfToken())) {
                return invalidCsrfToken();
            }
            setCurrentUser(ctx, session.username());
            return AuthorizationStatus.of(true);
        });
    }

    private static AuthorizationStatus invalidCsrfToken() {
        return AuthorizationStatus.ofFailure(
                (delegate, ctx1, req1, cause) -> HttpResponse.of(HttpStatus.FORBIDDEN,
                                                                 MediaType.PLAIN_TEXT_UTF_8,
                                                                 "Invalid CSRF token"));
    }

    private void setCurrentUser(ServiceRequestContext ctx, String username) {
        final List<String> roles =
                systemAdministrators.contains(username) ? LEVEL_SYSTEM_ADMIN
                                                       : LEVEL_USER;
        final User user = new User(username, roles);
        ctx.logBuilder().authenticatedUser("user/" + username);
        AuthUtil.setCurrentUser(ctx, user);
        HttpApiUtil.setVerboseResponses(ctx, user);
    }
}
