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
package com.linecorp.centraldogma.testing.internal.auth;

import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.INSECURE_SESSION_COOKIE_NAME;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.SECURE_SESSION_COOKIE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Base64.Encoder;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil;
import com.linecorp.centraldogma.server.metadata.Token;

/**
 * A utility class which helps to create messages for authentication.
 */
public final class TestAuthMessageUtil {

    public static final String USERNAME = "foo";
    public static final String PASSWORD = "bar";
    public static final String USERNAME2 = "foo2";
    public static final String PASSWORD2 = "bar2";
    public static final String WRONG_PASSWORD = "baz";
    public static final String WRONG_SESSION_ID = "00000000-0000-0000-0000-000000000000";

    private static final Encoder encoder = Base64.getEncoder();

    public static AggregatedHttpResponse login(WebClient client, String username, String password) {
        return client.execute(
                RequestHeaders.of(HttpMethod.POST, "/api/v1/login",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA),
                "grant_type=password&username=" + username + "&password=" + password,
                StandardCharsets.US_ASCII).aggregate().join();
    }

    public static AggregatedHttpResponse loginWithBasicAuth(WebClient client, String username,
                                                            String password) {
        final String token = "basic " + encoder.encodeToString(
                (username + ':' + password).getBytes(StandardCharsets.US_ASCII));
        return client.execute(RequestHeaders.of(HttpMethod.POST, "/api/v1/login",
                                                HttpHeaderNames.AUTHORIZATION, token))
                     .aggregate().join();
    }

    public static AggregatedHttpResponse logout(WebClient client, Cookie sessionCookie, String csrfToken) {
        return client.execute(
                RequestHeaders.builder(HttpMethod.POST, "/api/v1/logout")
                              .cookie(sessionCookie)
                              .set(SessionUtil.X_CSRF_TOKEN, csrfToken).build()).aggregate().join();
    }

    public static AggregatedHttpResponse usersMe(WebClient client, Cookie sessionCookie, String csrfToken) {
        return client.execute(
                RequestHeaders.builder(HttpMethod.GET, "/api/v0/users/me")
                              .cookie(sessionCookie)
                              .set(SessionUtil.X_CSRF_TOKEN, csrfToken).build()).aggregate().join();
    }

    public static String getAccessToken(WebClient client, String username, String password,
                                        boolean isSystemAdmin) {
        return getAccessToken(client, username, password, "testId", isSystemAdmin);
    }

    public static String getAccessToken(WebClient client, String username, String password,
                                        String appId, boolean isSystemAdmin) {
        final AggregatedHttpResponse response = login(client, username, password);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        final Cookie sessionCookie = getSessionCookie(response);
        final String csrfToken;
        try {
            csrfToken = Jackson.readTree(response.contentUtf8()).get("csrf_token").asText();
        } catch (JsonParseException e) {
            throw new RuntimeException(e);
        }

        final QueryParams params = QueryParams.builder()
                                              .add("appId", appId)
                                              .add("isSystemAdmin", String.valueOf(isSystemAdmin))
                                              .build();
        final Token token =
                client.blocking().prepare()
                      .post("/api/v1/tokens")
                      .cookie(sessionCookie)
                      .header(SessionUtil.X_CSRF_TOKEN, csrfToken)
                      .content(MediaType.FORM_DATA, params.toQueryString())
                      .asJson(Token.class, new ObjectMapper())
                      .execute()
                      .content();
        assertThat(token.secret()).isNotNull();
        return token.secret();
    }

    public static Cookie getSessionCookie(AggregatedHttpResponse response) {
        return getSessionCookie(response, false);
    }

    public static Cookie getSessionCookie(AggregatedHttpResponse response, boolean tlsEnabled) {
        final String cookieName = tlsEnabled ? SECURE_SESSION_COOKIE_NAME
                                             : INSECURE_SESSION_COOKIE_NAME;
        for (Cookie cookie : response.headers().cookies()) {
            if (cookie.name().equals(cookieName)) {
                return cookie;
            }
        }
        throw new IllegalStateException(cookieName + " cookie not found");
    }

    private TestAuthMessageUtil() {}
}
