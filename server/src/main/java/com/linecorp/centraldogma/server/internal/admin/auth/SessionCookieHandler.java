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
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.getJwtClaimsSetFromEncryptedCookie;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.getSessionIdFromCookie;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.sessionCookieName;
import static java.util.Objects.requireNonNull;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.nimbusds.jose.JWEDecrypter;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;

import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.auth.SessionKey;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;

public final class SessionCookieHandler {

    private final Supplier<Boolean> sessionPropagatorWritableChecker;
    private final String sessionCookieName;

    @Nullable
    private final SessionKey sessionKey;
    @Nullable
    private final DefaultJWTClaimsVerifier<?> verifier;
    @Nullable
    private final JWEDecrypter decrypter;

    public SessionCookieHandler(Supplier<Boolean> sessionPropagatorWritableChecker, boolean tlsEnabled,
                                EncryptionStorageManager encryptionStorageManager) {
        this.sessionPropagatorWritableChecker =
                requireNonNull(sessionPropagatorWritableChecker, "sessionPropagatorWritableChecker");
        sessionCookieName = sessionCookieName(tlsEnabled);
        requireNonNull(encryptionStorageManager, "encryptionStorageManager");
        if (encryptionStorageManager.encryptSessionCookie()) {
            sessionKey = encryptionStorageManager.getCurrentSessionKey().join();
            try {
                verifier = new DefaultJWTClaimsVerifier<>(new JWTClaimsSet.Builder()
                                                                  .issuer("dogma")
                                                                  .build(),
                                                          ImmutableSet.of("exp"));
                decrypter = new DirectDecrypter(sessionKey.encryptionKey());
            } catch (Throwable t) {
                // Should never reach here.
                throw new Error(t);
            }
        } else {
            sessionKey = null;
            verifier = null;
            decrypter = null;
        }
    }

    @Nullable
    public SessionInfo getSessionInfo(ServiceRequestContext ctx) {
        if (sessionKey != null) {
            assert verifier != null;
            assert decrypter != null;
            final JWTClaimsSet jwtClaimsSet = getJwtClaimsSetFromEncryptedCookie(
                    ctx, sessionCookieName, verifier, decrypter);
            if (jwtClaimsSet == null) {
                return null;
            }
            final Object objectSessionId = jwtClaimsSet.getClaim("sessionId");
            if (objectSessionId instanceof String) {
                return new SessionInfo((String) objectSessionId, null);
            } else {
                if (sessionPropagatorWritableChecker.get()) {
                    return null;
                }
                // In read-only mode, we support authentication using only the username claim.
                final String subject = jwtClaimsSet.getSubject();
                if (isNullOrEmpty(subject)) {
                    return null;
                }
                return new SessionInfo(null, subject);
            }
        }
        final String sessionId = getSessionIdFromCookie(ctx, sessionCookieName);
        return sessionId != null ? new SessionInfo(sessionId, null) : null;
    }

    public static final class SessionInfo {
        @Nullable
        private final String sessionId;
        @Nullable
        private final String username;

        SessionInfo(@Nullable String sessionId, @Nullable String username) {
            assert sessionId != null || username != null;
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
    }
}
