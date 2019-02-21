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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Base64.Encoder;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;

/**
 * A utility class which helps to create messages for authentication.
 */
public final class TestAuthMessageUtil {

    public static final String USERNAME = "foo";
    public static final String PASSWORD = "bar";
    public static final String WRONG_PASSWORD = "baz";
    public static final String WRONG_SESSION_ID = "00000000-0000-0000-0000-000000000000";
    public static final String MALFORMED_SESSION_ID = "not_a_session_id";

    private static final Encoder encoder = Base64.getEncoder();

    public static AggregatedHttpMessage login(HttpClient client, String username, String password) {
        return client.execute(
                HttpHeaders.of(HttpHeaderNames.METHOD, "POST",
                               HttpHeaderNames.PATH, "/api/v1/login",
                               HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA.toString()),
                "grant_type=password&username=" + username + "&password=" + password,
                StandardCharsets.US_ASCII).aggregate().join();
    }

    public static AggregatedHttpMessage loginWithBasicAuth(HttpClient client, String username,
                                                           String password) {
        return client.execute(
                HttpHeaders.of(HttpHeaderNames.METHOD, "POST",
                               HttpHeaderNames.PATH, "/api/v1/login",
                               HttpHeaderNames.AUTHORIZATION, "basic " + encoder.encodeToString(
                                (username + ':' + password).getBytes(StandardCharsets.US_ASCII))))
                     .aggregate().join();
    }

    public static AggregatedHttpMessage logout(HttpClient client, String sessionId) {
        return client.execute(
                HttpHeaders.of(HttpHeaderNames.METHOD, "POST",
                               HttpHeaderNames.PATH, "/api/v1/logout",
                               HttpHeaderNames.AUTHORIZATION,
                               "bearer " + sessionId)).aggregate().join();
    }

    public static AggregatedHttpMessage usersMe(HttpClient client, String sessionId) {
        return client.execute(
                HttpHeaders.of(HttpHeaderNames.METHOD, "GET",
                               HttpHeaderNames.PATH, "/api/v0/users/me",
                               HttpHeaderNames.AUTHORIZATION,
                               "bearer " + sessionId)).aggregate().join();
    }

    private TestAuthMessageUtil() {}
}
