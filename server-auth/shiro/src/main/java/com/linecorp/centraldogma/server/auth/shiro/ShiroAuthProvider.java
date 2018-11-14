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
package com.linecorp.centraldogma.server.auth.shiro;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.apache.shiro.config.Ini;
import org.apache.shiro.config.IniSecurityManagerFactory;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.session.mgt.DefaultSessionManager;
import org.apache.shiro.util.Factory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;
import com.linecorp.centraldogma.server.auth.AuthConfig;
import com.linecorp.centraldogma.server.auth.AuthProvider;
import com.linecorp.centraldogma.server.auth.Session;

/**
 * Apache Shiro based {@link AuthProvider} implementation.
 */
public final class ShiroAuthProvider implements AuthProvider {

    private final Service<HttpRequest, HttpResponse> loginApiService;
    private final Service<HttpRequest, HttpResponse> logoutApiService;

    ShiroAuthProvider(AuthConfig authConfig,
                      Ini config,
                      Supplier<String> sessionIdGenerator,
                      Function<Session, CompletableFuture<Void>> loginSessionPropagator,
                      Function<String, CompletableFuture<Void>> logoutSessionPropagator) {
        requireNonNull(authConfig, "authConfig");
        requireNonNull(config, "config");
        requireNonNull(sessionIdGenerator, "sessionIdGenerator");
        requireNonNull(loginSessionPropagator, "loginSessionPropagator");
        requireNonNull(logoutSessionPropagator, "logoutSessionPropagator");

        final SecurityManager securityManager = createSecurityManager(config, sessionIdGenerator);
        final Duration sessionValidDuration = Duration.ofMillis(authConfig.sessionTimeoutMillis());

        loginApiService = new LoginService(securityManager, authConfig.loginNameNormalizer(),
                                           loginSessionPropagator, sessionValidDuration);
        logoutApiService = new LogoutService(securityManager, logoutSessionPropagator);
    }

    private SecurityManager createSecurityManager(Ini config, Supplier<String> sessionIdGenerator) {
        final Factory<SecurityManager> factory = new IniSecurityManagerFactory(config) {
            @Override
            protected SecurityManager createDefaultInstance() {
                final DefaultSessionManager sessionManager = new DefaultSessionManager();
                // This session DAO is required to cache the session in a very short time, especially while
                // logging in to the Central Dogma server. After that, the general session manager provided
                // by Central Dogma server will be working for the session management.
                sessionManager.setSessionDAO(new LimitedMemorySessionDAO(sessionIdGenerator,
                                                                         64, Duration.ofHours(1)));

                final DefaultSecurityManager securityManager = new DefaultSecurityManager();
                securityManager.setSessionManager(sessionManager);

                return securityManager;
            }
        };
        return factory.getInstance();
    }

    @Nullable
    @Override
    public Service<HttpRequest, HttpResponse> loginApiService() {
        return loginApiService;
    }

    @Nullable
    @Override
    public Service<HttpRequest, HttpResponse> logoutApiService() {
        return logoutApiService;
    }
}
