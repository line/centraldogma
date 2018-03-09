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

import java.util.concurrent.CompletableFuture;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import com.linecorp.armeria.server.auth.OAuth2Token;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;

/**
 * A service to handle a logout request to Central Dogma Web admin service.
 */
public class LogoutService extends AbstractHttpService {

    private static final Logger logger = LoggerFactory.getLogger(LogoutService.class);

    private final CentralDogmaSecurityManager securityManager;
    private final CommandExecutor executor;

    public LogoutService(CentralDogmaSecurityManager securityManager, CommandExecutor executor) {
        this.securityManager = requireNonNull(securityManager, "securityManager");
        this.executor = requireNonNull(executor, "executor");
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        req.aggregate().thenAccept(aMsg -> {
            final OAuth2Token token = AuthTokenExtractors.OAUTH2.apply(aMsg.headers());
            if (token == null) {
                future.complete(HttpResponse.of(HttpStatus.OK));
                return;
            }

            ctx.blockingTaskExecutor().execute(() -> {
                final String sessionId = token.accessToken();

                // Need to set the thread-local security manager to silence
                // the UnavailableSecurityManagerException logged at DEBUG level.
                ThreadContext.bind(securityManager);
                try {
                    if (securityManager.sessionExists(sessionId)) {
                        final Subject currentUser = new Subject.Builder(securityManager)
                                .sessionCreationEnabled(false)
                                .sessionId(sessionId)
                                .buildSubject();

                        // Get the principal before logging out because otherwise it will be cleared out.
                        final Object principal = currentUser.getPrincipal();
                        currentUser.logout();
                        executor.execute(Command.removeSession(sessionId)).join();
                        logger.info("{} Logged out: {} ({})", ctx, principal, sessionId);
                    } else {
                        logger.debug("{} Tried to log out a non-existent session: {}", ctx, sessionId);
                    }
                    future.complete(HttpResponse.of(HttpStatus.OK));
                } catch (Throwable t) {
                    logger.warn("{} Failed to log out: {}", ctx, sessionId, t);
                    future.complete(HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
                } finally {
                    ThreadContext.unbindSecurityManager();
                }
            });
        }).exceptionally(voidFunction(cause -> {
            logger.warn("{} Unexpected exception:", ctx, cause);
            future.complete(HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
        }));
        return HttpResponse.from(future);
    }
}
