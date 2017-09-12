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

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.Subject;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import com.linecorp.armeria.server.auth.OAuth2Token;

/**
 * A service to handle a logout request to Central Dogma Web admin service.
 */
public class LogoutService extends AbstractHttpService {

    private final SecurityManager securityManager;

    public LogoutService(SecurityManager securityManager) {
        this.securityManager = requireNonNull(securityManager, "securityManager");
    }

    @Override
    protected void doPost(ServiceRequestContext ctx, HttpRequest req,
                          HttpResponseWriter res) throws Exception {
        req.aggregate().thenAccept(aMsg -> {
            final OAuth2Token token = AuthTokenExtractors.OAUTH2.apply(aMsg.headers());
            if (token == null) {
                res.respond(HttpStatus.OK);
                return;
            }

            ctx.blockingTaskExecutor().execute(() -> {
                try {
                    final Subject currentUser =
                            new Subject.Builder(securityManager).sessionId(token.accessToken())
                                                                .buildSubject();
                    currentUser.logout();
                } finally {
                    res.respond(HttpStatus.OK);
                }
            });
        }).exceptionally(voidFunction(unused -> res.respond(HttpStatus.OK)));
    }
}
