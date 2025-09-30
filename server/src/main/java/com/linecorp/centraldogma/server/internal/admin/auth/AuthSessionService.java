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
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.hash.Hashing;
import com.nimbusds.jose.JWEEncrypter;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.SignedJWT;
import com.spotify.futures.CompletableFutures;

import com.linecorp.centraldogma.common.ReadOnlyException;
import com.linecorp.centraldogma.server.auth.Session;
import com.linecorp.centraldogma.server.auth.SessionKey;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;

public final class AuthSessionService {

    private final Function<Session, CompletableFuture<Void>> loginSessionPropagator;
    private final Supplier<Boolean> sessionPropagatorWritableChecker;
    private final Duration sessionValidDuration;

    @Nullable
    private final SessionKey sessionKey;
    @Nullable
    private final JWSSigner signer;
    @Nullable
    private final JWEEncrypter encrypter;

    public AuthSessionService(
            Function<Session, CompletableFuture<Void>> loginSessionPropagator,
            Supplier<Boolean> sessionPropagatorWritableChecker,
            Duration sessionValidDuration,
            EncryptionStorageManager encryptionStorageManager) {
        this.loginSessionPropagator = requireNonNull(loginSessionPropagator, "loginSessionPropagator");
        this.sessionPropagatorWritableChecker =
                requireNonNull(sessionPropagatorWritableChecker, "sessionPropagatorWritableChecker");
        this.sessionValidDuration = requireNonNull(sessionValidDuration, "sessionValidDuration");
        requireNonNull(encryptionStorageManager, "encryptionStorageManager");

        if (encryptionStorageManager.encryptSessionCookie()) {
            sessionKey = encryptionStorageManager.getCurrentSessionKey().join();
            try {
                signer = new MACSigner(sessionKey.signingKey());
                encrypter = new DirectEncrypter(sessionKey.encryptionKey());
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to initialize AuthSessionService", t);
            }
        } else {
            sessionKey = null;
            signer = null;
            encrypter = null;
        }
    }

    /**
     * Creates a new session for the given user.
     */
    public CompletableFuture<LoginResult> create(String username, Supplier<String> sessionIdGenerator,
                                                 Supplier<String> csrfTokenGenerator) {
        if (!sessionPropagatorWritableChecker.get()) {
            // Read-only mode
            if (sessionKey == null) {
                return CompletableFutures.exceptionallyCompletedFuture(
                        new ReadOnlyException("Cannot login in read-only mode without session encryption"));
            }

            assert signer != null;
            assert encrypter != null;
            final String sessionKeyVersion = Integer.toString(sessionKey.version());
            final SignedJWT signedJwt =
                    SessionUtil.createSignedJwtInReadOnly(username, sessionKeyVersion, signer);
            // Generate a CSRF token by hashing the signature of the JWT to relate it to the session.
            // Even though the server is in read-only mode, we use the CSRF token for using
            // - logout API
            // - server status API (that isn't working at the moment but probably will work in the future)
            // - other APIs that require CSRF token in the future.
            final String csrfToken = Hashing.sha256().hashBytes(signedJwt.getSignature().decode()).toString();
            final String sessionCookieValue = createJwe(signedJwt.serialize(), sessionKeyVersion, encrypter);
            return CompletableFuture.completedFuture(new LoginResult(sessionCookieValue, csrfToken));
        }

        // Writable mode
        final String sessionId = sessionIdGenerator.get();
        final String csrfToken = csrfTokenGenerator.get();
        final Session newSession = new Session(sessionId, csrfToken, username, sessionValidDuration);

        return loginSessionPropagator.apply(newSession).thenApply(unused -> {
            final String sessionCookieValue;
            if (sessionKey != null) {
                assert signer != null;
                assert encrypter != null;
                sessionCookieValue = createSessionJwe(newSession,
                                                      Integer.toString(sessionKey.version()),
                                                      signer, encrypter);
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
