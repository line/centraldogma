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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Base64.Encoder;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;

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
    public static final String MALFORMED_SESSION_ID = "not_a_session_id";

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

    public static AggregatedHttpResponse logout(WebClient client, String sessionId) {
        return client.execute(
                RequestHeaders.of(HttpMethod.POST, "/api/v1/logout",
                                  HttpHeaderNames.AUTHORIZATION, "Bearer " + sessionId)).aggregate().join();
    }

    public static AggregatedHttpResponse usersMe(WebClient client, String sessionId) {
        return client.execute(
                RequestHeaders.of(HttpMethod.GET, "/api/v0/users/me",
                                  HttpHeaderNames.AUTHORIZATION, "Bearer " + sessionId)).aggregate().join();
    }

    public static String getAccessToken(WebClient client, String username, String password) {
        final AggregatedHttpResponse response = login(client, username, password);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        try {
            return Jackson.readValue(response.content().array(), AccessToken.class)
                          .accessToken();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private TestAuthMessageUtil() {}
}
