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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.CookieBuilder;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.HttpHeaderNames;
import org.jspecify.annotations.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AsciiString;

public final class SessionUtil {

    private static final Logger logger = LoggerFactory.getLogger(SessionUtil.class);

    // https://en.wikipedia.org/wiki/Cross-site_request_forgery#Cookie-to-header_token
    public static final AsciiString X_CSRF_TOKEN = HttpHeaderNames.of("X-CSRF-Token");

    public static Cookie createSessionIdCookie(String sessionId, boolean tlsEnabled, long cookieMaxAgeSecond) {
        final String sessionCookieName = sessionCookieName(tlsEnabled);

        // TODO(minwoox): Sign and Encrypt the cookie value.
        final CookieBuilder cookieBuilder = Cookie.secureBuilder(sessionCookieName, sessionId)
                                                  .maxAge(cookieMaxAgeSecond).path("/");
        if (!tlsEnabled) {
            cookieBuilder.secure(false);
        }
        return cookieBuilder.build();
    }

    public static String sessionCookieName(boolean tlsEnabled) {
        // Prefix the cookie name with "__Host-Http-".
        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Cookies#cookie_prefixes
        return tlsEnabled ? "__Host-Http-session-id" : "session-id";
    }

    @Nullable
    public static String getSessionIdFromCookie(ServiceRequestContext ctx, String sessionCookieName) {
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

        return sessionCookie.value();
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

    private SessionUtil() {}
}
