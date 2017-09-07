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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * A service to handle a login request to Central Dogma Web admin service.
 */
public class LoginService extends AbstractHttpService {

    private final SecurityManager securityManager;

    public LoginService(SecurityManager securityManager) {
        this.securityManager = requireNonNull(securityManager, "securityManager");
    }

    @Override
    protected void doPost(ServiceRequestContext ctx, HttpRequest req,
                          HttpResponseWriter res) throws Exception {
        req.aggregate().thenAccept(aMsg -> {
            final QueryStringDecoder decoder =
                    new QueryStringDecoder(aMsg.content().toStringUtf8(), false);
            final String username = Ascii.toUpperCase(decoder.parameters().get("username").get(0));
            final String password = decoder.parameters().get("password").get(0);
            final boolean rememberMe = Boolean.valueOf(decoder.parameters().get("remember_me").get(0));

            checkArgument(username != null, "Parameter username should not be null.");
            checkArgument(password != null, "Parameter password should not be null.");

            ctx.blockingTaskExecutor().execute(() -> {
                Subject currentUser = null;
                try {
                    currentUser = new Subject.Builder(securityManager)
                            .principals(new SimplePrincipalCollection(username, username))
                            .buildSubject();

                    final String sessionId = currentUser.getSession().getId().toString();
                    currentUser.login(new UsernamePasswordToken(username, password, rememberMe));

                    res.respond(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, sessionId);
                } catch (Exception e) {
                    if (currentUser != null) {
                        // To delete unnecessary session from Central Dogma session storage.
                        currentUser.logout();
                    }
                    res.respond(HttpStatus.UNAUTHORIZED);
                }
            });
        }).exceptionally(voidFunction(unused -> res.respond(HttpStatus.UNAUTHORIZED)));
    }
}
