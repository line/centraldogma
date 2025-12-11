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
package com.linecorp.centraldogma.server.auth;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V0_PATH_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static com.linecorp.centraldogma.server.auth.AuthProviderUtil.loginOrLogoutService;

import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpServiceWithRoutes;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Service;

/**
 * An interface which configures the authentication layer for the Central Dogma server.
 */
@FunctionalInterface
public interface AuthProvider {
    /**
     * A login page path for the web console. If a user, who has not logged into the web console yet,
     * opens the web console, the web browser would bring the user to the login page.
     */
    String LOGIN_PATH = "/link/auth/login";

    /**
     * A logout page path for the web console. If a user clicks the logout button on the navigation bar,
     * the web browser would bring the user to the logout page.
     */
    String LOGOUT_PATH = "/link/auth/logout";

    /**
     * A base path of the built-in web app.
     */
    String BUILTIN_WEB_BASE_PATH = "/web/auth";

    /**
     * A path which provides a built-in HTML login form to a user.
     */
    String BUILTIN_WEB_LOGIN_PATH = BUILTIN_WEB_BASE_PATH + "/login";

    /**
     * A path which provides a built-in HTML logout page to a user.
     */
    String BUILTIN_WEB_LOGOUT_PATH = BUILTIN_WEB_BASE_PATH + "/logout";

    /**
     * A set of {@link Route}s which handles a login request. It is necessary only if
     * an authentication protocol requires a login feature provided by the server.
     */
    Set<Route> LOGIN_API_ROUTES =
            ImmutableSet.of(Route.builder().exact(API_V0_PATH_PREFIX + "authenticate").build(),
                            Route.builder().exact(API_V1_PATH_PREFIX + "login").build());

    /**
     * A set of {@link Route}s which handles a logout request. It is necessary only if
     * an authentication protocol requires a logout feature provided by the server.
     */
    Set<Route> LOGOUT_API_ROUTES =
            ImmutableSet.of(Route.builder().exact(API_V0_PATH_PREFIX + "logout").build(),
                            Route.builder().exact(API_V1_PATH_PREFIX + "logout").build());

    /**
     * Returns a {@link Service} which handles a login request from a web browser. By default,
     * the browser would bring a user to the built-in web login page served on {@value BUILTIN_WEB_LOGIN_PATH}.
     */
    default HttpService webLoginService(AllowedUrisConfig allowedUrisConfig) {
        return loginOrLogoutService(allowedUrisConfig, BUILTIN_WEB_LOGIN_PATH);
    }

    /**
     * Returns a {@link Service} which handles a logout request from a web browser. By default,
     * the browser would bring a user to the built-in web logout page served on
     * {@value BUILTIN_WEB_LOGOUT_PATH}.
     */
    default HttpService webLogoutService(AllowedUrisConfig allowedUrisConfig) {
        return loginOrLogoutService(allowedUrisConfig, BUILTIN_WEB_LOGOUT_PATH);
    }

    /**
     * Returns a {@link Service} which handles a login request sent from the built-in web login page or
     * somewhere implemented by an {@link AuthProvider}. This service would be added to the server
     * with {@link #LOGIN_API_ROUTES} only if it is provided.
     */
    @Nullable
    default HttpService loginApiService() {
        return null;
    }

    /**
     * Returns a {@link Service} which handles a logout request sent from the built-in web logout page or
     * somewhere implemented by an {@link AuthProvider}. This service would be added to the server
     * with {@link #LOGOUT_API_ROUTES}. If it is not provided, a default service would be added
     * because the web console provides a logout button on the navigation bar by default.
     */
    @Nullable
    default HttpService logoutApiService() {
        return null;
    }

    /**
     * Returns additional {@link Service}s which are required for working this {@link AuthProvider}
     * well.
     */
    default Iterable<HttpServiceWithRoutes> moreServices() {
        return ImmutableList.of();
    }

    /**
     * Returns the parameters which are necessary for this {@link AuthProvider}.
     */
    AuthProviderParameters parameters();
}
