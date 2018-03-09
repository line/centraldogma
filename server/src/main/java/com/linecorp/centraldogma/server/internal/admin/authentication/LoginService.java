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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;

import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * A service to handle a login request to Central Dogma Web admin service.
 */
public class LoginService extends AbstractHttpService {

    private static final Logger logger = LoggerFactory.getLogger(LoginService.class);

    private final CentralDogmaSecurityManager securityManager;
    private final CommandExecutor executor;

    public LoginService(CentralDogmaSecurityManager securityManager, CommandExecutor executor) {
        this.securityManager = requireNonNull(securityManager, "securityManager");
        this.executor = requireNonNull(executor, "executor");
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        req.aggregate().thenAccept(aMsg -> {
            final QueryStringDecoder decoder =
                    new QueryStringDecoder(aMsg.content().toStringUtf8(), false);
            final String username = decoder.parameters().get("username").get(0);
            final String password = decoder.parameters().get("password").get(0);
            final boolean rememberMe = Boolean.valueOf(decoder.parameters().get("remember_me").get(0));

            checkArgument(username != null, "Parameter username should not be null.");
            checkArgument(password != null, "Parameter password should not be null.");

            ctx.blockingTaskExecutor().execute(() -> {
                // Need to set the thread-local security manager to silence
                // the UnavailableSecurityManagerException logged at DEBUG level.
                ThreadContext.bind(securityManager);
                Subject currentUser = null;
                boolean success = false;
                try {
                    currentUser = new Subject.Builder(securityManager).buildSubject();
                    currentUser.login(new UsernamePasswordToken(username, password, rememberMe));

                    final String sessionId = currentUser.getSession(false).getId().toString();
                    final SimpleSession session = securityManager.getSerializableSession(sessionId);
                    executor.execute(Command.createSession(session)).join();
                    success = true;

                    logger.info("{} Logged in: {} ({})", ctx, username, sessionId);
                    future.complete(HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, sessionId));
                } catch (IncorrectCredentialsException e) {
                    // Not authorized
                    logger.debug("{} Incorrect login: {}", ctx, username);
                    future.complete(HttpResponse.of(HttpStatus.UNAUTHORIZED));
                } catch (Throwable t) {
                    logger.warn("{} Failed to authenticate: {}", ctx, username, t);
                    future.complete(HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
                } finally {
                    try {
                        if (!success && currentUser != null) {
                            // Delete failed session.
                            currentUser.logout();
                        }
                    } finally {
                        ThreadContext.unbindSecurityManager();
                    }
                }
            });
        }).exceptionally(voidFunction(cause -> {
            logger.warn("{} Unexpected exception:", ctx, cause);
            future.complete(HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
        }));
        return HttpResponse.from(future);
    }
}
