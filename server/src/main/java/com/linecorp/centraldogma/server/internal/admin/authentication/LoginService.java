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
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.newHttpResponseException;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import com.linecorp.armeria.server.auth.BasicToken;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
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
    private final Function<String, String> loginNameNormalizer;
    private final Cache<String, AccessToken> cache;

    public LoginService(CentralDogmaSecurityManager securityManager, CommandExecutor executor,
                        Function<String, String> loginNameNormalizer, Cache<String, AccessToken> cache) {
        this.securityManager = requireNonNull(securityManager, "securityManager");
        this.executor = requireNonNull(executor, "executor");
        this.loginNameNormalizer = requireNonNull(loginNameNormalizer, "loginNameNormalizer");
        this.cache = requireNonNull(cache, "cache");
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        req.aggregate().thenAccept(aMsg -> {
            final UsernamePasswordToken usernamePassword;
            try {
                usernamePassword = usernamePassword(aMsg);
            } catch (HttpResponseException e) {
                future.complete(e.httpResponse());
                return;
            }

            ctx.blockingTaskExecutor().execute(() -> {
                // Need to set the thread-local security manager to silence
                // the UnavailableSecurityManagerException logged at DEBUG level.
                ThreadContext.bind(securityManager);
                Subject currentUser = null;
                boolean success = false;
                try {
                    final AccessToken currentUserToken = currentUserTokenIfPresent(usernamePassword);
                    if (currentUserToken != null) {
                        future.complete(HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                                        Jackson.writeValueAsBytes(currentUserToken)));
                        return;
                    }

                    currentUser = new Subject.Builder(securityManager).buildSubject();
                    currentUser.login(usernamePassword);

                    final Session currentUserSession = currentUser.getSession(false);
                    final long expiresIn = currentUserSession.getTimeout();
                    final String sessionId = currentUserSession.getId().toString();
                    final SimpleSession session = securityManager.getSerializableSession(sessionId);
                    executor.execute(Command.createSession(session)).join();
                    success = true;

                    logger.info("{} Logged in: {} ({})", ctx, usernamePassword.getUsername(), sessionId);

                    final AccessToken accessToken = new AccessToken(sessionId, expiresIn);
                    cache.put(usernamePassword.getUsername(), accessToken);
                    future.complete(HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                                    Jackson.writeValueAsBytes(accessToken)));
                } catch (IncorrectCredentialsException e) {
                    // Not authorized
                    logger.debug("{} Incorrect login: {}", ctx, usernamePassword.getUsername());
                    future.complete(HttpResponse.of(HttpStatus.UNAUTHORIZED));
                } catch (Throwable t) {
                    logger.warn("{} Failed to authenticate: {}", ctx, usernamePassword.getUsername(), t);
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

    /**
     * Returns {@link UsernamePasswordToken} which holds a username and a password.
     */
    private UsernamePasswordToken usernamePassword(AggregatedHttpMessage req) {
        // check the Basic HTTP authentication first (https://tools.ietf.org/html/rfc7617)
        final BasicToken basicToken = AuthTokenExtractors.BASIC.apply(req.headers());
        if (basicToken != null) {
            return new UsernamePasswordToken(basicToken.username(), basicToken.password());
        }

        final MediaType mediaType = req.headers().contentType();
        if (mediaType != MediaType.FORM_DATA) {
            return throwResponseException("invalid_request",
                                          "request was missing the '" + MediaType.FORM_DATA + "'.");
        }

        final Map<String, List<String>> parameters = new QueryStringDecoder(
                req.content().toStringUtf8(), false).parameters();

        // assume that the grant_type is "password"
        final List<String> usernames = parameters.get("username");
        final List<String> passwords = parameters.get("password");
        if (usernames != null && passwords != null) {
            final String username = usernames.get(0);
            final String password = passwords.get(0);
            return new UsernamePasswordToken(loginNameNormalizer.apply(username), password);
        }

        return throwResponseException("invalid_request", "request must contain username and password.");
    }

    @Nullable
    private AccessToken currentUserTokenIfPresent(UsernamePasswordToken usernamePassword) {
        securityManager.authenticate(usernamePassword);
        // Because securityManager.authenticate does not throw any Exception, the user is authenticated.
        final AccessToken currentUserToken = cache.getIfPresent(usernamePassword.getUsername());

        if (currentUserToken != null) {
            final long currentTimeMillis = System.currentTimeMillis();
            if (currentUserToken.deadline() >
                currentTimeMillis + Math.min(securityManager.globalSessionTimeout(), 60000 /* 1 minute */)) {
                return new AccessToken(currentUserToken.accessToken(),
                                       currentUserToken.deadline() - currentTimeMillis);
            }
        }
        return null;
    }

    private static <T> T throwResponseException(String error, String errorDescription) {
        final ImmutableMap<String, String> errorMessage = ImmutableMap.of(
                "error", error, "error_description", errorDescription);
        throw newHttpResponseException(HttpStatus.BAD_REQUEST, Jackson.valueToTree(errorMessage));
    }
}
