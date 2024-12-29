/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.centraldogma.server.auth.saml;

import static com.linecorp.centraldogma.server.metadata.User.LEVEL_SYSTEM_ADMIN;
import static com.linecorp.centraldogma.server.metadata.User.LEVEL_USER;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.auth.OAuth2Token;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import com.linecorp.armeria.server.auth.Authorizer;
import com.linecorp.centraldogma.server.auth.SessionManager;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionTokenAuthorizer;
import com.linecorp.centraldogma.server.metadata.User;

/**
 * A decorator to grant level from saml authentication result. It is one another implementation of
 * {@link SessionTokenAuthorizer}.
 */
public class SessionGroupTokenAuthorizer implements Authorizer<HttpRequest> {

    private final SessionManager sessionManager;
    private final Set<String> adminUserGroups;

    /**
     * Constructs a SessionGroupTokenAuthorizer with the specified session manager and admin user groups
     * from saml login result.
     *
     * @param sessionManager the session manager used for managing sessions
     * @param adminUserGroups a set of admin user group identifiers
     * @throws NullPointerException if sessionManager or adminUserGroups is null
     */
    public SessionGroupTokenAuthorizer(SessionManager sessionManager, Set<String> adminUserGroups) {
        this.sessionManager = requireNonNull(sessionManager, "sessionManager");
        this.adminUserGroups = requireNonNull(adminUserGroups, "adminUserGroups");
    }

    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest data) {
        final OAuth2Token token = AuthTokenExtractors.oAuth2().apply(data.headers());
        if (token == null) {
            return completedFuture(false);
        }

        return sessionManager.get(token.accessToken())
                             .thenApply(session -> {
                                 if (session == null) {
                                     return false;
                                 }

                                 final SamlSession samlSession;
                                 try {
                                     samlSession = session.castRawSession();
                                 } catch (Exception e) {
                                     return false;
                                 }

                                 final List<String> roles =
                                         samlSession.groups()
                                                    .stream().anyMatch(adminUserGroups::contains) ?
                                         LEVEL_SYSTEM_ADMIN : LEVEL_USER;

                                 final String username = session.username();

                                 final User user = new User(username, roles);
                                 ctx.logBuilder().authenticatedUser("user/" + username);
                                 AuthUtil.setCurrentUser(ctx, user);
                                 return true;
                             });
    }
}
