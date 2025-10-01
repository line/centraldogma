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

import java.time.Duration;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.hash.Hashing;
import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEDecrypter;
import com.nimbusds.jose.JWEEncrypter;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTClaimsSet.Builder;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.CookieBuilder;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.auth.Session;

import io.netty.util.AsciiString;

public final class SessionUtil {

    private static final Logger logger = LoggerFactory.getLogger(SessionUtil.class);

    // https://en.wikipedia.org/wiki/Cross-site_request_forgery#Cookie-to-header_token
    public static final AsciiString X_CSRF_TOKEN = HttpHeaderNames.of("X-CSRF-Token");

    public static final long DEFAULT_READ_ONLY_MODE_SESSION_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    public static boolean constantTimeEquals(@Nullable String a, @Nullable String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    public static Cookie createSessionCookie(String sessionCookieName, String sessionCookieValue,
                                             boolean tlsEnabled, long cookieMaxAgeSecond) {
        final CookieBuilder cookieBuilder = Cookie.secureBuilder(sessionCookieName, sessionCookieValue)
                                                  .maxAge(cookieMaxAgeSecond).path("/");
        if (!tlsEnabled) {
            cookieBuilder.secure(false);
        }
        return cookieBuilder.build();
    }

    public static String sessionCookieName(boolean tlsEnabled, boolean encryptSessionCookie) {
        // Prefix the cookie name with "__Host-Http-".
        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Cookies#cookie_prefixes
        final String prefix = tlsEnabled ? "__Host-Http-" : "";
        return encryptSessionCookie ? prefix + "session-jwt" : prefix + "session-id";
    }

    @Nullable
    public static SignedJWT getSignedJwtFromEncryptedCookie(
            ServiceRequestContext ctx, String sessionCookieName,
            JWEDecrypter decrypter) {
        final Cookie sessionCookie = findSessionCookie(ctx, sessionCookieName);
        if (sessionCookie == null) {
            return null;
        }
        final String cookieValue = sessionCookie.value();
        return decryptAndGetSignedJwt(ctx, decrypter, cookieValue);
    }

    static SignedJWT decryptAndGetSignedJwt(ServiceRequestContext ctx, JWEDecrypter decrypter,
                                            String cookieValue) {
        final JWEObject jweObject;
        try {
            jweObject = JWEObject.parse(cookieValue);
            jweObject.decrypt(decrypter);
        } catch (Throwable t) {
            logger.trace("Failed to parse the session cookie. ctx={}", ctx, t);
            return null;
        }

        final Payload payload = jweObject.getPayload();
        final String jwsString = payload.toString();
        try {
            return SignedJWT.parse(jwsString);
        } catch (Throwable t) {
            logger.trace("Failed to parse the inner JWS. ctx={}", ctx, t);
            return null;
        }
    }

    @Nullable
    static JWTClaimsSet getJwtClaimsSetFromSignedJwt(ServiceRequestContext ctx,
                                                     SignedJWT signedJWT,
                                                     DefaultJWTClaimsVerifier<?> verifier) {
        final JWTClaimsSet jwtClaimsSet;
        try {
            jwtClaimsSet = signedJWT.getJWTClaimsSet();
        } catch (Throwable t) {
            logger.trace("Failed to parse the inner JWS. ctx={}", ctx, t);
            return null;
        }

        try {
            verifier.verify(jwtClaimsSet, null);
            return jwtClaimsSet;
        } catch (BadJWTException e) {
            logger.trace("Invalid claim set in the inner JWS. ctx={}", ctx, e);
            return null;
        }
    }

    static String csrfTokenFromSignedJwt(SignedJWT signedJwt) {
        return Hashing.sha256().hashBytes(signedJwt.getSignature().decode()).toString();
    }

    public static boolean validateCsrfToken(ServiceRequestContext ctx, HttpRequest req,
                                            @javax.annotation.Nullable String expectedCsrfToken) {
        // Check the token when the method is not safe:
        // https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#javascript-automatically-including-csrf-tokens-as-an-ajax-request-header
        if (isSafeMethod(req)) {
            return true;
        }
        final String csrfToken = req.headers().get(X_CSRF_TOKEN, "");
        if (!constantTimeEquals(csrfToken, expectedCsrfToken)) {
            logger.trace("CSRF token mismatch: tokenPresent={}, ctx={}", !isNullOrEmpty(csrfToken), ctx);
            return false;
        }
        return true;
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

    @Nullable
    public static String getSessionIdFromCookie(ServiceRequestContext ctx, String sessionCookieName) {
        final Cookie sessionCookie = findSessionCookie(ctx, sessionCookieName);
        if (sessionCookie == null) {
            return null;
        }

        return sessionCookie.value();
    }

    @Nullable
    private static Cookie findSessionCookie(ServiceRequestContext ctx, String sessionCookieName) {
        final Cookies cookies = ctx.request().headers().cookies();
        if (cookies.isEmpty()) {
            logger.trace("Cookie header is missing. ctx={}", ctx);
            return null;
        }

        final Cookie sessionCookie = findSessionCookie(cookies, sessionCookieName);
        if (sessionCookie == null) {
            logger.trace("Session cookie is missing. ctx={}", ctx);
            return null;
        }
        return sessionCookie;
    }

    @Nullable
    private static Cookie findSessionCookie(Cookies cookies, String sessionCookieName) {
        for (Cookie cookie : cookies) {
            if (sessionCookieName.equals(cookie.name())) {
                return cookie;
            }
        }
        return null;
    }

    public static String createSessionJwe(Session session, String sessionKeyVersion,
                                          JWSSigner signer, JWEEncrypter encrypter) {
        return createJwe(createSignedJwt(session, sessionKeyVersion, signer).serialize(),
                         sessionKeyVersion, encrypter);
    }

    public static SignedJWT createSignedJwt(Session session, String sessionKeyVersion, JWSSigner signer) {
        final JWTClaimsSet claimsSet = new Builder()
                .subject(session.username())
                .claim("sessionId", session.id())
                .issuer("dogma") // TODO(minwoox): Use domain name if necessary.
                .issueTime(Date.from(session.creationTime()))
                .expirationTime(Date.from(session.expirationTime()))
                .build();
        return createSignedJwt(sessionKeyVersion, signer, claimsSet);
    }

    private static SignedJWT createSignedJwt(
            String sessionKeyVersion, JWSSigner signer, JWTClaimsSet claimsSet) {
        final JWSHeader jwsHeader = new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT)
                .keyID(sessionKeyVersion)
                .build();

        final SignedJWT signedJWT = new SignedJWT(jwsHeader, claimsSet);
        try {
            signedJWT.sign(signer);
        } catch (JOSEException e) {
            // This should never happen.
            throw new Error();
        }

        return signedJWT;
    }

    public static SignedJWT createSignedJwtInReadOnly(
            String username, String sessionKeyVersion, JWSSigner signer) {
        final long now = System.currentTimeMillis();
        final JWTClaimsSet claimsSet = new Builder()
                .subject(username)
                .issuer("dogma") // TODO(minwoox): Use domain name.
                .issueTime(new Date(now))
                .expirationTime(new Date(now + DEFAULT_READ_ONLY_MODE_SESSION_TIMEOUT_MILLIS))
                .build();
        return createSignedJwt(sessionKeyVersion, signer, claimsSet);
    }

    public static String createJwe(String signedJwt, String sessionKeyVersion, JWEEncrypter encrypter) {
        final JWEHeader jweHeader = new JWEHeader.Builder(JWEAlgorithm.DIR, EncryptionMethod.A256GCM)
                .contentType("JWT")
                .keyID(sessionKeyVersion)
                .build();

        final Payload payload = new Payload(signedJwt);
        final JWEObject jweObject = new JWEObject(jweHeader, payload);
        try {
            jweObject.encrypt(encrypter);
        } catch (JOSEException e) {
            // This should never happen.
            throw new Error();
        }
        return jweObject.serialize();
    }

    private SessionUtil() {}
}
