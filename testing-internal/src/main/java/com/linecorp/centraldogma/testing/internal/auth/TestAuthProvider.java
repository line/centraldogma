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

import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.createSessionIdCookie;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.getSessionIdFromCookie;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.sessionCookieName;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.WRONG_SESSION_ID;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import com.linecorp.centraldogma.server.auth.AuthProvider;
import com.linecorp.centraldogma.server.auth.AuthProviderParameters;
import com.linecorp.centraldogma.server.auth.Session;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil;

import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * An {@link AuthProvider} which is used for testing.
 */
public class TestAuthProvider implements AuthProvider {

    private final AuthProviderParameters parameters;
    private final Supplier<String> sessionIdGenerator;
    private final Function<Session, CompletableFuture<Void>> loginSessionPropagator;
    private final Function<String, CompletableFuture<Void>> logoutSessionPropagator;

    public TestAuthProvider(AuthProviderParameters parameters) {
        this.parameters = parameters;
        requireNonNull(parameters, "parameters");
        sessionIdGenerator = parameters.sessionIdGenerator();
        loginSessionPropagator = parameters.loginSessionPropagator();
        logoutSessionPropagator = parameters.logoutSessionPropagator();
    }

    @Nullable
    @Override
    public HttpService loginApiService() {
        return new LoginService();
    }

    @Nullable
    @Override
    public HttpService logoutApiService() {
        return new LogoutService();
    }

    @Override
    public AuthProviderParameters parameters() {
        return parameters;
    }

    class LoginService implements HttpService {
        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
                final AggregatedHttpRequest msg = req.aggregate().join();
                final String username;
                final String password;
                final BasicToken basicToken = AuthTokenExtractors.basic()
                                                                 .apply(RequestHeaders.of(msg.headers()));
                if (basicToken != null) {
                    username = basicToken.username();
                    password = basicToken.password();
                } else {
                    final QueryStringDecoder decoder = new QueryStringDecoder(msg.contentUtf8(), false);
                    username = decoder.parameters().get("username").get(0);
                    password = decoder.parameters().get("password").get(0);
                }
                if ((USERNAME.equals(username) && PASSWORD.equals(password)) ||
                    (USERNAME2.equals(username) && PASSWORD2.equals(password))) {
                    final String sessionId = sessionIdGenerator.get();
                    final Session session =
                            new Session(sessionId, "csrfToken", username, Duration.ofSeconds(60));
                    loginSessionPropagator.apply(session).join();
                    final Cookie cookie = createSessionIdCookie(sessionId, parameters.tlsEnabled(), 60);
                    final ResponseHeaders responseHeaders =
                            ResponseHeaders.builder(HttpStatus.OK)
                                           .contentType(MediaType.JSON_UTF_8)
                                           .cookie(cookie).build();

                    final String csrfTokenString = "{\"csrf_token\":\"csrfToken\"}";
                    return HttpResponse.of(responseHeaders, HttpData.ofUtf8(csrfTokenString));
                } else {
                    return HttpResponse.of(HttpStatus.UNAUTHORIZED);
                }
            }, ctx.blockingTaskExecutor()));
        }
    }

    class LogoutService implements HttpService {
        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final String sessionId =
                    getSessionIdFromCookie(ctx, sessionCookieName(parameters.tlsEnabled()));
            if (sessionId != null && !WRONG_SESSION_ID.equals(sessionId)) {
                if (!"csrfToken".equals(req.headers().get(SessionUtil.X_CSRF_TOKEN))) {
                    return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.PLAIN_TEXT_UTF_8,
                                           "Invalid CSRF token");
                }

                logoutSessionPropagator.apply(sessionId).join();
                return HttpResponse.of(HttpStatus.OK);
            }

            return HttpResponse.of(HttpStatus.NO_CONTENT);
        }
    }
}
