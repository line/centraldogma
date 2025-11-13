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

import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.createSessionCookie;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.sessionCookieName;
import static com.linecorp.centraldogma.server.internal.api.HttpApiUtil.throwResponse;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

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
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ServerCacheControl;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import com.linecorp.centraldogma.common.ReadOnlyException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.auth.Session;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthSessionService;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthSessionService.LoginResult;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;

import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * A service to handle a login request to Central Dogma Web admin service.
 */
final class ShiroLoginService extends AbstractHttpService {
    private static final Logger logger = LoggerFactory.getLogger(ShiroLoginService.class);

    private final SecurityManager securityManager;
    private final Function<String, String> loginNameNormalizer;
    private final Supplier<String> csrfTokenGenerator;
    private final long cookieMaxAgeSecond;
    private final boolean tlsEnabled;
    private final String sessionCookieName;
    private final AuthSessionService authSessionService;

    ShiroLoginService(SecurityManager securityManager,
                      Function<String, String> loginNameNormalizer,
                      Supplier<String> csrfTokenGenerator,
                      Function<Session, CompletableFuture<Void>> loginSessionPropagator,
                      BooleanSupplier sessionPropagatorWritableChecker, Duration sessionValidDuration,
                      boolean tlsEnabled, EncryptionStorageManager encryptionStorageManager) {
        this.securityManager = requireNonNull(securityManager, "securityManager");
        this.loginNameNormalizer = requireNonNull(loginNameNormalizer, "loginNameNormalizer");
        this.csrfTokenGenerator = requireNonNull(csrfTokenGenerator, "csrfTokenGenerator");
        // Make the cookie expire a bit earlier than the session itself.
        cookieMaxAgeSecond = sessionValidDuration.minusMinutes(1).getSeconds();
        this.tlsEnabled = tlsEnabled;
        sessionCookieName = sessionCookieName(tlsEnabled, encryptionStorageManager.encryptSessionCookie());
        authSessionService = new AuthSessionService(loginSessionPropagator,
                                                    sessionPropagatorWritableChecker,
                                                    sessionValidDuration,
                                                    encryptionStorageManager);
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        // TODO(minwoox): Apply login CSRF token.
        // https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#possible-csrf-vulnerabilities-in-login-forms
        return HttpResponse.of(
                req.aggregate()
                   .thenApply(msg -> usernamePassword(ctx, msg))
                   .thenComposeAsync(usernamePassword -> {
                       ThreadContext.bind(securityManager);
                       Subject currentUser = null;
                       try {
                           currentUser = new Builder(securityManager).buildSubject();
                           currentUser.login(usernamePassword);
                           final String username = usernamePassword.getUsername();
                           final Subject loginUser = currentUser;
                           final Supplier<String> sessionIdGenerator = () -> loginUser.getSession(false).getId()
                                                                                      .toString();
                           return authSessionService.create(username, sessionIdGenerator, csrfTokenGenerator)
                                                    .handle((loginResult, cause) -> {
                                                        if (cause != null) {
                                                            ThreadContext.bind(securityManager);
                                                            logoutUserQuietly(ctx, loginUser);
                                                            ThreadContext.unbindSecurityManager();

                                                            final Throwable peeled = Exceptions.peel(cause);
                                                            if (peeled instanceof ReadOnlyException) {
                                                                return HttpResponse.of(
                                                                        HttpStatus.SERVICE_UNAVAILABLE,
                                                                        MediaType.PLAIN_TEXT_UTF_8,
                                                                        peeled.getMessage());
                                                            }
                                                            return HttpApiUtil.newResponse(
                                                                    ctx, HttpStatus.INTERNAL_SERVER_ERROR,
                                                                    peeled);
                                                        }

                                                        logger.debug("{} Logged in: {}", ctx, username);
                                                        return httpResponse(loginResult);
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

    private HttpResponse httpResponse(LoginResult loginResult) {
        final Cookie cookie = createSessionCookie(sessionCookieName, loginResult.sessionCookieValue(),
                                                  tlsEnabled, cookieMaxAgeSecond);
        final ResponseHeaders responseHeaders =
                ResponseHeaders.builder(HttpStatus.OK)
                               .contentType(MediaType.JSON_UTF_8)
                               .set(HttpHeaderNames.CACHE_CONTROL,
                                    ServerCacheControl.DISABLED.asHeaderValue())
                               .cookie(cookie).build();
        final String body;
        try {
            body = Jackson.writeValueAsString(
                    ImmutableMap.of("csrf_token", loginResult.csrfToken()));
        } catch (JsonProcessingException e) {
            // This should never happen.
            throw new Error(e);
        }

        return HttpResponse.of(responseHeaders, HttpData.ofUtf8(body));
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
        // check the Basic HTTP authentication first (https://datatracker.ietf.org/doc/html/rfc7617)
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
