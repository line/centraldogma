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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.X_CSRF_TOKEN;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.constantTimeEquals;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.sessionCookieName;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger logger = LoggerFactory.getLogger(DefaultLogoutService.class);

    private final Function<String, CompletableFuture<Void>> logoutSessionPropagator;
    private final SessionManager sessionManager;
    private final SessionCookieHandler sessionCookieHandler;

    private final Cookie invalidatingCookie;

    public DefaultLogoutService(Function<String, CompletableFuture<Void>> logoutSessionPropagator,
                                Supplier<Boolean> sessionPropagatorWritableChecker,
                                SessionManager sessionManager, boolean tlsEnabled,
                                EncryptionStorageManager encryptionStorageManager) {
        this.logoutSessionPropagator = requireNonNull(logoutSessionPropagator, "logoutSessionPropagator");
        this.sessionManager = requireNonNull(sessionManager, "sessionManager");
        sessionCookieHandler = new SessionCookieHandler(
                sessionPropagatorWritableChecker, tlsEnabled, encryptionStorageManager);
        requireNonNull(encryptionStorageManager, "encryptionStorageManager");

        // Create a cookie template for invalidation.
        final CookieBuilder cookieBuilder = Cookie.secureBuilder(sessionCookieName(tlsEnabled), "")
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
            assert username != null;
            return HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK)
                                                  .cookie(invalidatingCookie)
                                                  .build());
        }

        return HttpResponse.of(sessionManager.get(sessionId).thenCompose(session -> {
            if (session == null) {
                return UnmodifiableFuture.completedFuture(HttpResponse.of(HttpStatus.NO_CONTENT));
            }

            final String csrfToken = req.headers().get(X_CSRF_TOKEN);
            if (!constantTimeEquals(csrfToken, session.csrfToken())) {
                logger.trace("CSRF token mismatch: tokenPresent={}, ctx={}", !isNullOrEmpty(csrfToken), ctx);
                return UnmodifiableFuture.completedFuture(
                        HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.PLAIN_TEXT_UTF_8,
                                        "Invalid CSRF token"));
            }

            return invalidateSession(ctx, sessionId).thenCompose(
                    unused -> logoutSessionPropagator.apply(sessionId).thenApply(unused2 -> {
                        return HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK)
                                                              .cookie(invalidatingCookie)
                                                              .build());
                    }));
        }));
    }

    protected CompletableFuture<Void> invalidateSession(ServiceRequestContext ctx, String sessionId) {
        return UnmodifiableFuture.completedFuture(null);
    }
}
