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

package com.linecorp.centraldogma.server.auth.shiro;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.DefaultSessionKey;
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
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;

/**
 * A service to handle a logout request to Central Dogma Web admin service.
 */
final class LogoutService extends AbstractHttpService {

    private static final Logger logger = LoggerFactory.getLogger(LogoutService.class);

    private final SecurityManager securityManager;
    private final Function<String, CompletableFuture<Void>> logoutSessionPropagator;

    LogoutService(SecurityManager securityManager,
                  Function<String, CompletableFuture<Void>> logoutSessionPropagator) {
        this.securityManager = requireNonNull(securityManager, "securityManager");
        this.logoutSessionPropagator = requireNonNull(logoutSessionPropagator, "logoutSessionPropagator");
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.from(
                req.aggregate().thenApply(msg -> AuthTokenExtractors.OAUTH2.apply(msg.headers()))
                   .thenApplyAsync(token -> {
                       if (token == null) {
                           return HttpResponse.of(HttpStatus.OK);
                       }

                       final String sessionId = token.accessToken();
                       // Need to set the thread-local security manager to silence
                       // the UnavailableSecurityManagerException logged at DEBUG level.
                       ThreadContext.bind(securityManager);
                       try {
                           final Session session = securityManager.getSession(new DefaultSessionKey(sessionId));
                           if (session != null) {
                               final Subject currentUser = new Subject.Builder(securityManager)
                                       .sessionCreationEnabled(false)
                                       .sessionId(sessionId)
                                       .buildSubject();

                               // Get the principal before logging out because otherwise it will be cleared out.
                               final String username = (String) currentUser.getPrincipal();
                               currentUser.logout();
                           }
                       } catch (Throwable t) {
                           logger.warn("{} Failed to log out: {}", ctx, sessionId, t);
                       } finally {
                           ThreadContext.unbindSecurityManager();
                       }

                       // Do not care the exception raised before, then try to remove session from the
                       // Central Dogma session manager. If it succeeded, the session ID has been also
                       // invalidated so that the logout request with the session ID would not come here again.
                       return HttpResponse.from(
                               logoutSessionPropagator.apply(sessionId).handle((unused, cause) -> {
                                   if (cause != null) {
                                       return HttpResponse.of(HttpApiUtil.newResponse(
                                               ctx, HttpStatus.INTERNAL_SERVER_ERROR, cause));
                                   } else {
                                       return HttpResponse.of(HttpStatus.OK);
                                   }
                               }));
                   }, ctx.blockingTaskExecutor()));
    }
}
