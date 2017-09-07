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

package com.linecorp.centraldogma.server.admin.authentication;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.Subject;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import com.linecorp.armeria.server.auth.Authorizer;
import com.linecorp.armeria.server.auth.OAuth2Token;

/**
 * A decorator to check whether the request holds a valid token. If it holds a valid token, this
 * decorator would find a session belonging to the token and attach it to the service context attributes.
 */
public class CentralDogmaTokenAuthorizer implements Authorizer<HttpRequest> {

    private final SecurityManager securityManager;

    public CentralDogmaTokenAuthorizer(SecurityManager securityManager) {
        this.securityManager = requireNonNull(securityManager, "securityManager");
    }

    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest data) {
        final OAuth2Token token = AuthTokenExtractors.OAUTH2.apply(data.headers());
        if (token == null) {
            return completedFuture(true);
        }

        final CompletableFuture<Boolean> res = new CompletableFuture<>();
        ctx.blockingTaskExecutor().execute(() -> {
            try {
                final Subject currentUser =
                        new Subject.Builder(securityManager).sessionId(token.accessToken())
                                                            .buildSubject();
                if (currentUser != null) {
                    final User user = new User(currentUser.getPrincipal().toString(), User.USER_ROLE);
                    AuthenticationUtil.setCurrentUser(ctx, user);
                }
            } finally {
                res.complete(true);
            }
        });
        return res;
    }
}
