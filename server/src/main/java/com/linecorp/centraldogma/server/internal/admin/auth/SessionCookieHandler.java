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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.csrfTokenFromSignedJwt;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.findSessionCookie;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.getJwtClaimsSetFromSignedJwt;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.getSessionIdFromCookie;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.getSessionKeyVersion;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.getSignedJwtFromEncryptedCookie;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.sessionCookieName;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import com.google.common.collect.ImmutableSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTClaimsSet.Builder;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.auth.SessionKey;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;

public final class SessionCookieHandler {

    private final BooleanSupplier sessionPropagatorWritableChecker;
    private final EncryptionStorageManager encryptionStorageManager;
    private final String sessionCookieName;

    @Nullable
    private volatile SessionKey sessionKey;
    @Nullable
    private final DefaultJWTClaimsVerifier<SecurityContext> jwtClaimsVerifier;

    public SessionCookieHandler(BooleanSupplier sessionPropagatorWritableChecker, boolean tlsEnabled,
                                EncryptionStorageManager encryptionStorageManager) {
        this.sessionPropagatorWritableChecker =
                requireNonNull(sessionPropagatorWritableChecker, "sessionPropagatorWritableChecker");
        this.encryptionStorageManager = requireNonNull(encryptionStorageManager, "encryptionStorageManager");
        sessionCookieName = sessionCookieName(tlsEnabled, encryptionStorageManager.encryptSessionCookie());
        if (encryptionStorageManager.encryptSessionCookie()) {
            sessionKey = encryptionStorageManager.getCurrentSessionKey().join();
            encryptionStorageManager.addSessionKeyListener(sessionKey -> this.sessionKey = sessionKey);
            jwtClaimsVerifier = new DefaultJWTClaimsVerifier<>(new Builder().issuer("dogma").build(),
                                                               ImmutableSet.of("exp"));
        } else {
            sessionKey = null;
            jwtClaimsVerifier = null;
        }
    }

    public CompletableFuture<SessionInfo> getSessionInfo(ServiceRequestContext ctx) {
        if (encryptionStorageManager.encryptSessionCookie()) {
            final SessionKey sessionKey = this.sessionKey;
            assert sessionKey != null;
            final Cookie sessionCookie = findSessionCookie(ctx, sessionCookieName);
            if (sessionCookie == null) {
                return UnmodifiableFuture.completedFuture(null);
            }
            final int sessionKeyVersion = getSessionKeyVersion(ctx, sessionCookie.value());
            if (sessionKeyVersion <= 0) {
                return UnmodifiableFuture.completedFuture(null);
            }

            if (sessionKeyVersion == sessionKey.version()) {
                return CompletableFuture.completedFuture(getSessionInfo(ctx, sessionKey));
            }

            return encryptionStorageManager.getSessionKey(sessionKeyVersion).thenApply(fetchedSessionKey -> {
                return getSessionInfo(ctx, fetchedSessionKey);
            });
        }
        final String sessionId = getSessionIdFromCookie(ctx, sessionCookieName);
        return sessionId != null ? CompletableFuture.completedFuture(new SessionInfo(sessionId, null, null))
                                 : UnmodifiableFuture.completedFuture(null);
    }

    @Nullable
    private SessionInfo getSessionInfo(ServiceRequestContext ctx, SessionKey sessionKey) {
        final SignedJWT signedJwt =
                getSignedJwtFromEncryptedCookie(ctx, sessionCookieName, sessionKey.decrypter());
        if (signedJwt == null) {
            return null;
        }
        assert jwtClaimsVerifier != null;
        final JWTClaimsSet jwtClaimsSet =
                getJwtClaimsSetFromSignedJwt(ctx, signedJwt, sessionKey, jwtClaimsVerifier);
        if (jwtClaimsSet == null) {
            return null;
        }
        final Object objectSessionId = jwtClaimsSet.getClaim("sessionId");
        if (objectSessionId instanceof String) {
            return new SessionInfo((String) objectSessionId, null, null);
        }
        if (sessionPropagatorWritableChecker.getAsBoolean()) {
            return null;
        }
        // In read-only mode, we support authentication using only the username claim.
        final String subject = jwtClaimsSet.getSubject();
        if (isNullOrEmpty(subject)) {
            return null;
        }
        final String csrfTokenFromSignedJwt = csrfTokenFromSignedJwt(signedJwt);
        return new SessionInfo(null, subject, csrfTokenFromSignedJwt);
    }

    public static final class SessionInfo {
        @Nullable
        private final String sessionId;
        @Nullable
        private final String username;
        @Nullable
        private final String csrfTokenFromSignedJwt;

        SessionInfo(@Nullable String sessionId, @Nullable String username,
                    @Nullable String csrfTokenFromSignedJwt) {
            this.csrfTokenFromSignedJwt = csrfTokenFromSignedJwt;
            assert sessionId != null || (username != null && csrfTokenFromSignedJwt != null);
            this.sessionId = sessionId;
            this.username = username;
        }

        @Nullable
        public String sessionId() {
            return sessionId;
        }

        @Nullable
        public String username() {
            return username;
        }

        @Nullable
        public String csrfTokenFromSignedJwt() {
            return csrfTokenFromSignedJwt;
        }
    }
}
