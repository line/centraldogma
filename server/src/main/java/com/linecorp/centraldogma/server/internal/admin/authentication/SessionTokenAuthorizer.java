/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.admin.authentication;

import static com.linecorp.centraldogma.server.internal.admin.authentication.User.LEVEL_ADMIN;
import static com.linecorp.centraldogma.server.internal.admin.authentication.User.LEVEL_USER;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import com.linecorp.armeria.server.auth.Authorizer;
import com.linecorp.armeria.server.auth.OAuth2Token;

/**
 * A decorator to check whether the request holds a valid token. If it holds a valid token, this
 * decorator would find a session belonging to the token and attach it to the service context attributes.
 */
public class SessionTokenAuthorizer implements Authorizer<HttpRequest> {

    private static final Logger logger = LoggerFactory.getLogger(SessionTokenAuthorizer.class);

    private final CentralDogmaSecurityManager securityManager;
    private final Set<String> administrators;

    public SessionTokenAuthorizer(CentralDogmaSecurityManager securityManager,
                                  Set<String> administrators) {
        this.securityManager = requireNonNull(securityManager, "securityManager");
        this.administrators = requireNonNull(administrators, "administrators");
    }

    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest data) {
        final OAuth2Token token = AuthTokenExtractors.OAUTH2.apply(data.headers());
        if (token == null) {
            return completedFuture(false);
        }

        final CompletableFuture<Boolean> res = new CompletableFuture<>();
        ctx.blockingTaskExecutor().execute(() -> {
            final String sessionId = token.accessToken();
            boolean isAuthenticated = false;
            try {
                if (!securityManager.sessionExists(sessionId)) {
                    logNonExistentSession(sessionId);
                    return;
                }

                final Subject currentUser =
                        new Subject.Builder(securityManager).sessionCreationEnabled(false)
                                                            .sessionId(sessionId)
                                                            .buildSubject();
                final Object principal = currentUser != null ? currentUser.getPrincipal() : null;
                if (principal == null) {
                    logNonExistentSession(sessionId);
                    return;
                }

                final String p = principal.toString();
                final User user = new User(p, administrators.contains(p) ? LEVEL_ADMIN : LEVEL_USER);
                AuthenticationUtil.setCurrentUser(ctx, user);
                isAuthenticated = true;
            } catch (Throwable t) {
                logger.warn("Failed to authorize a session: {}", sessionId, t);
            } finally {
                res.complete(isAuthenticated);
            }
        });
        return res;
    }

    private static void logNonExistentSession(String sessionId) {
        logger.debug("Non-existent session: {}", sessionId);
    }
}
