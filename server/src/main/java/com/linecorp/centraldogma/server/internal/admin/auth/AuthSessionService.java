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

import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.createJwe;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.createSessionJwe;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.csrfTokenFromSignedJwt;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.nimbusds.jwt.SignedJWT;
import com.spotify.futures.CompletableFutures;

import com.linecorp.centraldogma.common.ReadOnlyException;
import com.linecorp.centraldogma.server.auth.Session;
import com.linecorp.centraldogma.server.auth.SessionKey;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;

public final class AuthSessionService {

    private final Function<Session, CompletableFuture<Void>> loginSessionPropagator;
    private final BooleanSupplier sessionPropagatorWritableChecker;
    private final Duration sessionValidDuration;

    private final boolean encryptSessionCookie;
    @Nullable
    private volatile SessionKey sessionKey;

    public AuthSessionService(
            Function<Session, CompletableFuture<Void>> loginSessionPropagator,
            BooleanSupplier sessionPropagatorWritableChecker,
            Duration sessionValidDuration,
            EncryptionStorageManager encryptionStorageManager) {
        this.loginSessionPropagator = requireNonNull(loginSessionPropagator, "loginSessionPropagator");
        this.sessionPropagatorWritableChecker =
                requireNonNull(sessionPropagatorWritableChecker, "sessionPropagatorWritableChecker");
        this.sessionValidDuration = requireNonNull(sessionValidDuration, "sessionValidDuration");
        requireNonNull(encryptionStorageManager, "encryptionStorageManager");

        encryptSessionCookie = encryptionStorageManager.encryptSessionCookie();
        if (encryptSessionCookie) {
            sessionKey = encryptionStorageManager.getCurrentSessionKey().join();
            encryptionStorageManager.addSessionKeyListener(sessionKey -> {
                this.sessionKey = sessionKey;
            });
        } else {
            sessionKey = null;
        }
    }

    /**
     * Creates a new session for the given user.
     */
    public CompletableFuture<LoginResult> create(String username, Supplier<String> sessionIdGenerator,
                                                 Supplier<String> csrfTokenGenerator) {
        if (!sessionPropagatorWritableChecker.getAsBoolean()) {
            // Read-only mode
            if (!encryptSessionCookie) {
                return CompletableFutures.exceptionallyCompletedFuture(
                        new ReadOnlyException("Cannot login in read-only mode without session encryption"));
            }
            final SessionKey sessionKey = this.sessionKey;
            assert sessionKey != null;
            final String sessionKeyVersion = Integer.toString(sessionKey.version());
            final SignedJWT signedJwt =
                    SessionUtil.createSignedJwtInReadOnly(username, sessionKeyVersion, sessionKey.signer());
            // Generate a CSRF token by hashing the signature of the JWT to relate it to the session.
            // Even though the server is in read-only mode, we use the CSRF token for using
            // - logout API
            // - server status API (that isn't working at the moment but probably will work in the future)
            // - other APIs that require CSRF token in the future.
            final String csrfToken = csrfTokenFromSignedJwt(signedJwt);
            final String sessionCookieValue = createJwe(signedJwt.serialize(), sessionKeyVersion,
                                                        sessionKey.encrypter());
            return CompletableFuture.completedFuture(new LoginResult(sessionCookieValue, csrfToken));
        }

        // Writable mode
        final String sessionId = sessionIdGenerator.get();
        final String csrfToken = csrfTokenGenerator.get();
        final Session newSession = new Session(sessionId, csrfToken, username, sessionValidDuration);

        return loginSessionPropagator.apply(newSession).thenApply(unused -> {
            final String sessionCookieValue;
            if (encryptSessionCookie) {
                final SessionKey sessionKey = this.sessionKey;
                assert sessionKey != null;
                sessionCookieValue = createSessionJwe(newSession,
                                                      Integer.toString(sessionKey.version()),
                                                      sessionKey.signer(), sessionKey.encrypter());
            } else {
                sessionCookieValue = sessionId;
            }
            return new LoginResult(sessionCookieValue, csrfToken);
        });
    }

    public static final class LoginResult {
        private final String sessionCookieValue;
        private final String csrfToken;

        LoginResult(String sessionCookieValue, String csrfToken) {
            this.sessionCookieValue = sessionCookieValue;
            this.csrfToken = csrfToken;
        }

        public String sessionCookieValue() {
            return sessionCookieValue;
        }

        public String csrfToken() {
            return csrfToken;
        }
    }
}
