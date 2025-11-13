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
import java.util.function.Supplier;

import org.apache.shiro.config.Ini;
import org.apache.shiro.config.IniSecurityManagerFactory;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.session.mgt.DefaultSessionManager;
import org.apache.shiro.util.Factory;

import com.linecorp.armeria.server.HttpService;
import com.linecorp.centraldogma.server.auth.AuthConfig;
import com.linecorp.centraldogma.server.auth.AuthProvider;
import com.linecorp.centraldogma.server.auth.AuthProviderParameters;

/**
 * Apache Shiro based {@link AuthProvider} implementation.
 */
public final class ShiroAuthProvider implements AuthProvider {

    private final AuthProviderParameters parameters;
    private final HttpService loginApiService;
    private final HttpService logoutApiService;

    ShiroAuthProvider(AuthProviderParameters parameters, Ini config) {
        this.parameters = requireNonNull(parameters, "parameters");
        final AuthConfig authConfig = parameters.authConfig();
        requireNonNull(config, "config");

        final SecurityManager securityManager = createSecurityManager(config, parameters.sessionIdGenerator());
        final Duration sessionValidDuration = Duration.ofMillis(authConfig.sessionTimeoutMillis());

        loginApiService = new ShiroLoginService(securityManager, authConfig.loginNameNormalizer(),
                                                parameters.sessionIdGenerator(),
                                                parameters.loginSessionPropagator(),
                                                parameters.sessionPropagatorWritableChecker(),
                                                sessionValidDuration,
                                                parameters.tlsEnabled(),
                                                parameters.encryptionStorageManager());
        logoutApiService = new ShiroLogoutService(securityManager, parameters.logoutSessionPropagator(),
                                                  parameters.sessionPropagatorWritableChecker(),
                                                  parameters.sessionManager(), parameters.tlsEnabled(),
                                                  parameters.encryptionStorageManager());
    }

    private static SecurityManager createSecurityManager(Ini config, Supplier<String> sessionIdGenerator) {
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

    @Override
    public HttpService loginApiService() {
        return loginApiService;
    }

    @Override
    public HttpService logoutApiService() {
        return logoutApiService;
    }

    @Override
    public AuthProviderParameters parameters() {
        return parameters;
    }
}
