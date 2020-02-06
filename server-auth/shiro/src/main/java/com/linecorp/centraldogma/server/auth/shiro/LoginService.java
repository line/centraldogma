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

import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.throwResponse;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.Subject.Builder;
import org.apache.shiro.util.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import com.linecorp.armeria.server.auth.BasicToken;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
import com.linecorp.centraldogma.server.auth.Session;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;

import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * A service to handle a login request to Central Dogma Web admin service.
 */
final class LoginService extends AbstractHttpService {
    private static final Logger logger = LoggerFactory.getLogger(LoginService.class);

    private final SecurityManager securityManager;
    private final Function<String, String> loginNameNormalizer;
    private final Function<Session, CompletableFuture<Void>> loginSessionPropagator;
    private final Duration sessionValidDuration;

    LoginService(SecurityManager securityManager,
                 Function<String, String> loginNameNormalizer,
                 Function<Session, CompletableFuture<Void>> loginSessionPropagator,
                 Duration sessionValidDuration) {
        this.securityManager = requireNonNull(securityManager, "securityManager");
        this.loginNameNormalizer = requireNonNull(loginNameNormalizer, "loginNameNormalizer");
        this.loginSessionPropagator = requireNonNull(loginSessionPropagator, "loginSessionPropagator");
        this.sessionValidDuration = requireNonNull(sessionValidDuration, "sessionValidDuration");
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.from(
                req.aggregate()
                   .thenApply(msg -> usernamePassword(ctx, msg))
                   .thenComposeAsync(usernamePassword -> {
                       ThreadContext.bind(securityManager);
                       Subject currentUser = null;
                       try {
                           currentUser = new Builder(securityManager).buildSubject();
                           currentUser.login(usernamePassword);

                           final org.apache.shiro.session.Session session = currentUser.getSession(false);
                           final String sessionId = session.getId().toString();
                           final Session newSession =
                                   new Session(sessionId, usernamePassword.getUsername(),
                                               sessionValidDuration);
                           final Subject loginUser = currentUser;
                           // loginSessionPropagator will propagate the authenticated session to all replicas
                           // in the cluster.
                           return loginSessionPropagator.apply(newSession).handle((unused, cause) -> {
                               if (cause != null) {
                                   ThreadContext.bind(securityManager);
                                   logoutUserQuietly(ctx, loginUser);
                                   ThreadContext.unbindSecurityManager();
                                   return HttpApiUtil.newResponse(ctx, HttpStatus.INTERNAL_SERVER_ERROR,
                                                                  Exceptions.peel(cause));
                               }

                               logger.debug("{} Logged in: {} ({})",
                                            ctx, usernamePassword.getUsername(), sessionId);

                               // expires_in means valid seconds of the token from the creation.
                               final AccessToken accessToken =
                                       new AccessToken(sessionId, sessionValidDuration.getSeconds());
                               try {
                                   return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                                          Jackson.writeValueAsBytes(accessToken));
                               } catch (JsonProcessingException e) {
                                   return HttpApiUtil.newResponse(ctx, HttpStatus.INTERNAL_SERVER_ERROR, e);
                               }
                           });
                       } catch (IncorrectCredentialsException e) {
                           // Not authorized
                           logger.debug("{} Incorrect password: {}", ctx, usernamePassword.getUsername());
                           return CompletableFuture.completedFuture(
                                   HttpApiUtil.newResponse(ctx, HttpStatus.UNAUTHORIZED, "Incorrect password"));
                       } catch (UnknownAccountException e) {
                           logger.debug("{} unknown account: {}", ctx, usernamePassword.getUsername());
                           return CompletableFuture.completedFuture(
                                   HttpApiUtil.newResponse(ctx, HttpStatus.UNAUTHORIZED, "unknown account"));
                       } catch (Throwable t) {
                           logger.warn("{} Failed to authenticate: {}", ctx, usernamePassword.getUsername(), t);
                           return CompletableFuture.completedFuture(
                                   HttpApiUtil.newResponse(ctx, HttpStatus.INTERNAL_SERVER_ERROR, t));
                       } finally {
                           logoutUserQuietly(ctx, currentUser);
                           ThreadContext.unbindSecurityManager();
                       }
                   }, ctx.blockingTaskExecutor()));
    }

    private static void logoutUserQuietly(ServiceRequestContext ctx, @Nullable Subject user) {
        try {
            if (user != null && !user.isAuthenticated()) {
                user.logout();
            }
        } catch (Exception cause) {
            logger.debug("{} Failed to logout a user: {}", ctx, user, cause);
        }
    }

    /**
     * Returns {@link UsernamePasswordToken} which holds a username and a password.
     */
    private UsernamePasswordToken usernamePassword(ServiceRequestContext ctx, AggregatedHttpRequest req) {
        // check the Basic HTTP authentication first (https://tools.ietf.org/html/rfc7617)
        final BasicToken basicToken = AuthTokenExtractors.basic().apply(RequestHeaders.of(req.headers()));
        if (basicToken != null) {
            return new UsernamePasswordToken(basicToken.username(), basicToken.password());
        }

        final MediaType mediaType = req.headers().contentType();
        if (mediaType != MediaType.FORM_DATA) {
            return throwResponse(ctx, HttpStatus.BAD_REQUEST,
                                 "The content type of a login request must be '%s'.", MediaType.FORM_DATA);
        }

        final Map<String, List<String>> parameters = new QueryStringDecoder(req.contentUtf8(),
                                                                            false).parameters();
        // assume that the grant_type is "password"
        final List<String> usernames = parameters.get("username");
        final List<String> passwords = parameters.get("password");
        if (usernames != null && passwords != null) {
            final String username = usernames.get(0);
            final String password = passwords.get(0);
            return new UsernamePasswordToken(loginNameNormalizer.apply(username), password);
        }

        return throwResponse(ctx, HttpStatus.BAD_REQUEST,
                             "A login request must contain username and password.");
    }
}
