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

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.WRONG_SESSION_ID;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import com.linecorp.armeria.server.auth.BasicToken;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
import com.linecorp.centraldogma.server.auth.AuthProvider;
import com.linecorp.centraldogma.server.auth.AuthProviderParameters;
import com.linecorp.centraldogma.server.auth.Session;

import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * An {@link AuthProvider} which is used for testing.
 */
public class TestAuthProvider implements AuthProvider {

    private final Supplier<String> sessionIdGenerator;
    private final Function<Session, CompletableFuture<Void>> loginSessionPropagator;
    private final Function<String, CompletableFuture<Void>> logoutSessionPropagator;

    public TestAuthProvider(AuthProviderParameters parameters) {
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

    class LoginService implements HttpService {
        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.from(CompletableFuture.supplyAsync(() -> {
                final AggregatedHttpRequest msg = req.aggregate().join();
                final String username;
                final String password;
                final BasicToken basicToken = AuthTokenExtractors.BASIC.apply(RequestHeaders.of(msg.headers()));
                if (basicToken != null) {
                    username = basicToken.username();
                    password = basicToken.password();
                } else {
                    final QueryStringDecoder decoder = new QueryStringDecoder(msg.contentUtf8(), false);
                    username = decoder.parameters().get("username").get(0);
                    password = decoder.parameters().get("password").get(0);
                }
                if (USERNAME.equals(username) && PASSWORD.equals(password)) {
                    final String sessionId = sessionIdGenerator.get();
                    final Session session =
                            new Session(sessionId, username, Duration.ofSeconds(60));
                    loginSessionPropagator.apply(session).join();
                    final AccessToken accessToken = new AccessToken(sessionId, 60);
                    try {
                        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                               Jackson.writeValueAsBytes(accessToken));
                    } catch (JsonProcessingException e) {
                        throw new Error(e);
                    }
                } else {
                    return HttpResponse.of(HttpStatus.UNAUTHORIZED);
                }
            }, ctx.blockingTaskExecutor()));
        }
    }

    class LogoutService implements HttpService {
        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return HttpResponse.from(CompletableFuture.supplyAsync(() -> {
                final AggregatedHttpRequest msg = req.aggregate().join();
                final String sessionId =
                        AuthTokenExtractors.OAUTH2.apply(RequestHeaders.of(msg.headers())).accessToken();
                if (!WRONG_SESSION_ID.equals(sessionId)) {
                    logoutSessionPropagator.apply(sessionId).join();
                }
                return HttpResponse.of(HttpStatus.OK);
            }, ctx.blockingTaskExecutor()));
        }
    }
}
