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

import static com.linecorp.centraldogma.server.auth.AuthConfig.DEFAULT_SESSION_TIMEOUT_MILLIS;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.DEFAULT_READ_ONLY_MODE_SESSION_TIMEOUT_MILLIS;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.createSignedJwt;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.createSignedJwtInReadOnly;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.getJwtClaimsSetFromEncryptedCookie;
import static com.nimbusds.jose.JOSEObjectType.JWT;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.time.Duration;
import java.util.Date;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableSet;
import com.nimbusds.jose.JWEDecrypter;
import com.nimbusds.jose.JWEEncrypter;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTClaimsSet.Builder;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.auth.Session;
import com.linecorp.centraldogma.server.auth.SessionKey;
import com.linecorp.centraldogma.server.auth.SessionMasterKey;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;

class SessionUtilTest {

    @TempDir
    private static File rootDir;

    @SuppressWarnings("NotNullFieldNotInitialized")
    private static EncryptionStorageManager encryptionStorageManager;

    @BeforeAll
    static void setup() {
        encryptionStorageManager = EncryptionStorageManager.of(new File(rootDir, "rocksdb").toPath(), true);
        final SessionMasterKey sessionMasterKey =
                encryptionStorageManager.generateSessionMasterKey().join();
        encryptionStorageManager.storeSessionMasterKey(sessionMasterKey);
    }

    @AfterAll
    static void tearDown() {
        encryptionStorageManager.close();
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void signAndEncrypt(boolean readOnly) throws Exception {
        final SessionKey sessionKey = encryptionStorageManager.getCurrentSessionKey().join();
        final String version = Integer.toString(sessionKey.version());
        final Date now = new Date();

        final String signedJwt;
        final MACSigner signer = new MACSigner(sessionKey.signingKey());
        if (readOnly) {
            signedJwt = createSignedJwtInReadOnly("foo", version, signer).serialize();
        } else {
            final Session session = new Session("foo-id", "csrfToken", "foo", Duration.ofHours(8));
            signedJwt = createSignedJwt(session, version, signer).serialize();
        }

        final SignedJWT parsedJWT = SignedJWT.parse(signedJwt);
        final JWSHeader parsedHeader = parsedJWT.getHeader();
        assertThat(parsedHeader.getAlgorithm().getName()).isEqualTo("HS256");
        assertThat(parsedHeader.getType()).isEqualTo(JWT);
        assertThat(parsedHeader.getKeyID()).isEqualTo("1");

        verifyJwtClaimsSet(parsedJWT.getJWTClaimsSet(), now, readOnly);

        final JWEEncrypter encrypter = new DirectEncrypter(sessionKey.encryptionKey());
        final String jwe = SessionUtil.createJwe(signedJwt, version, encrypter);

        final DefaultJWTClaimsVerifier<SecurityContext> verifier =
                new DefaultJWTClaimsVerifier<>(new Builder()
                                                       .issuer("dogma")
                                                       .build(),
                                               ImmutableSet.of("exp"));
        final JWEDecrypter decrypter = new DirectDecrypter(sessionKey.encryptionKey());
        final JWTClaimsSet jwtClaimsSetFromEncryptedCookie = getJwtClaimsSetFromEncryptedCookie(
                ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/")),
                verifier, decrypter, jwe);
        assertThat(jwtClaimsSetFromEncryptedCookie).isNotNull();
        verifyJwtClaimsSet(jwtClaimsSetFromEncryptedCookie, now, readOnly);
    }

    private static void verifyJwtClaimsSet(JWTClaimsSet jwtClaimsSet, Date now, boolean readOnly) {
        assertThat(jwtClaimsSet.getSubject()).isEqualTo("foo");
        assertThat(jwtClaimsSet.getIssuer()).isEqualTo("dogma");
        if (readOnly) {
            assertThat(jwtClaimsSet.getClaim("sessionId")).isNull();
        } else {
            assertThat(jwtClaimsSet.getClaim("sessionId")).isEqualTo("foo-id");
        }
        // Buffer for time difference
        assertThat(jwtClaimsSet.getIssueTime()).isBetween(
                new Date(now.getTime() - 1000), new Date(now.getTime() + 1000));
        final long sessionValidDurationMillis = readOnly ? DEFAULT_READ_ONLY_MODE_SESSION_TIMEOUT_MILLIS
                                                         : DEFAULT_SESSION_TIMEOUT_MILLIS;
        assertThat(jwtClaimsSet.getExpirationTime()).isBetween(
                new Date(now.getTime() + sessionValidDurationMillis - 1000),
                new Date(now.getTime() + sessionValidDurationMillis + 1000));
    }
}
