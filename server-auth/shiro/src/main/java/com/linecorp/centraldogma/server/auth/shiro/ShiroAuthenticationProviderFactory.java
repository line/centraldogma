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

import java.util.function.Function;

import org.apache.shiro.config.Ini;

import com.linecorp.centraldogma.server.auth.AuthenticationConfig;
import com.linecorp.centraldogma.server.auth.AuthenticationProvider;
import com.linecorp.centraldogma.server.auth.AuthenticationProviderFactory;
import com.linecorp.centraldogma.server.auth.AuthenticationProviderParameters;

/**
 * A factory for creating an Apache Shiro based {@link AuthenticationProvider}.
 */
public final class ShiroAuthenticationProviderFactory implements AuthenticationProviderFactory {

    private final Function<AuthenticationConfig, Ini> iniConfigResolver;

    /**
     * Creates a new instance with the default {@link Ini} config resolver.
     */
    public ShiroAuthenticationProviderFactory() {
        this(ShiroAuthenticationProviderFactory::fromConfig);
    }

    /**
     * Creates a new instance with the specified {@code iniConfigResolver}.
     */
    public ShiroAuthenticationProviderFactory(Function<AuthenticationConfig, Ini> iniConfigResolver) {
        this.iniConfigResolver = requireNonNull(iniConfigResolver, "iniConfigResolver");
    }

    @Override
    public AuthenticationProvider create(AuthenticationProviderParameters parameters) {
        requireNonNull(parameters, "parameters");
        return new ShiroAuthenticationProvider(parameters.authConfig(),
                                               iniConfigResolver.apply(parameters.authConfig()),
                                               parameters.sessionIdGenerator(),
                                               parameters.loginSessionPropagator(),
                                               parameters.logoutSessionPropagator());
    }

    private static Ini fromConfig(AuthenticationConfig cfg) {
        try {
            final String iniPath = cfg.properties(String.class);
            return Ini.fromResourcePath(iniPath);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create " + Ini.class.getSimpleName(), e);
        }
    }
}
