/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.admin.service;

import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.sessionCookieName;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.validateCsrfToken;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.CookieBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.auth.SessionManager;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionCookieHandler;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionCookieHandler.SessionInfo;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;

public class DefaultLogoutService extends AbstractHttpService {

    private final Function<String, CompletableFuture<Void>> logoutSessionPropagator;
    private final SessionManager sessionManager;
    private final SessionCookieHandler sessionCookieHandler;

    private final Cookie invalidatingCookie;

    public DefaultLogoutService(Function<String, CompletableFuture<Void>> logoutSessionPropagator,
                                BooleanSupplier sessionPropagatorWritableChecker,
                                SessionManager sessionManager, boolean tlsEnabled,
                                EncryptionStorageManager encryptionStorageManager) {
        this.logoutSessionPropagator = requireNonNull(logoutSessionPropagator, "logoutSessionPropagator");
        this.sessionManager = requireNonNull(sessionManager, "sessionManager");
        sessionCookieHandler = new SessionCookieHandler(
                sessionPropagatorWritableChecker, tlsEnabled, encryptionStorageManager);
        requireNonNull(encryptionStorageManager, "encryptionStorageManager");

        // Create a cookie template for invalidation.
        final String sessionCookieName = sessionCookieName(tlsEnabled,
                                                           encryptionStorageManager.encryptSessionCookie());
        final CookieBuilder cookieBuilder = Cookie.secureBuilder(sessionCookieName, "")
                                                  .maxAge(0).path("/");
        if (!tlsEnabled) {
            cookieBuilder.secure(false);
        }
        invalidatingCookie = cookieBuilder.build();
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final SessionInfo sessionInfo = sessionCookieHandler.getSessionInfo(ctx);
        if (sessionInfo == null) {
            // Return 204 https://stackoverflow.com/questions/36220029/http-status-to-return-after-trying-to-logout-without-being-logged-in
            return HttpResponse.of(HttpStatus.NO_CONTENT);
        }
        final String sessionId = sessionInfo.sessionId();
        if (sessionId == null) {
            final String username = sessionInfo.username();
            final String csrfTokenFromSignedJwt = sessionInfo.csrfTokenFromSignedJwt();
            assert username != null;
            assert csrfTokenFromSignedJwt != null;
            if (!validateCsrfToken(ctx, req, csrfTokenFromSignedJwt)) {
                return invalidCsrfTokenResponse();
            }
            return HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK)
                                                  .cookie(invalidatingCookie)
                                                  .build());
        }

        return HttpResponse.of(sessionManager.get(sessionId).thenCompose(session -> {
            if (session == null) {
                return UnmodifiableFuture.completedFuture(HttpResponse.of(HttpStatus.NO_CONTENT));
            }

            if (!validateCsrfToken(ctx, req, session.csrfToken())) {
                return UnmodifiableFuture.completedFuture(invalidCsrfTokenResponse());
            }

            return invalidateSession(ctx, sessionId).thenCompose(
                    unused -> logoutSessionPropagator.apply(sessionId).thenApply(unused2 -> {
                        return HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK)
                                                              .cookie(invalidatingCookie)
                                                              .build());
                    }));
        }));
    }

    private static HttpResponse invalidCsrfTokenResponse() {
        return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.PLAIN_TEXT_UTF_8, "Invalid CSRF token");
    }

    protected CompletableFuture<Void> invalidateSession(ServiceRequestContext ctx, String sessionId) {
        return UnmodifiableFuture.completedFuture(null);
    }
}
