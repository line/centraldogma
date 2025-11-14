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

package com.linecorp.centraldogma.server.internal.admin.service;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.ServerCacheControl;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.auth.AuthProvider;
import com.linecorp.centraldogma.server.auth.SessionManager;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionCookieHandler;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil;
import com.linecorp.centraldogma.server.internal.admin.util.RestfulJsonResponseConverter;
import com.linecorp.centraldogma.server.internal.api.AbstractService;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;

/**
 * Annotated service object for managing users.
 */
@ResponseConverter(RestfulJsonResponseConverter.class)
public class UserService extends AbstractService {

    @Nullable
    private final SessionManager sessionManager;
    @Nullable
    private final SessionCookieHandler sessionCookieHandler;

    public UserService(@Nullable SessionManager sessionManager, boolean tlsEnabled, CommandExecutor executor,
                       @Nullable AuthProvider authProvider, EncryptionStorageManager encryptionStorageManager) {
        super(executor);
        this.sessionManager = sessionManager;
        if (authProvider != null)  {
            assert sessionManager != null;
            sessionCookieHandler = new SessionCookieHandler(
                    authProvider.parameters().sessionPropagatorWritableChecker(),
                    tlsEnabled, encryptionStorageManager);
        } else {
            sessionCookieHandler = null;
        }
    }

    /**
     * GET /users/me
     * Returns a login {@link User} if the user is authorized. Otherwise, {@code 401 Unauthorized} HTTP
     * response is sent. If the user has a valid session, the CSRF token is included in the response
     * header {@code X-CSRF-Token}.
     */
    @Get("/users/me")
    public HttpResponse usersMe(ServiceRequestContext ctx) throws Exception {
        final User user = AuthUtil.currentUser();
        final HttpData body = HttpData.wrap(Jackson.writeValueAsBytes(user));
        if (sessionManager != null) {
            assert sessionCookieHandler != null;
            return HttpResponse.of(sessionCookieHandler.getSessionInfo(ctx).thenApply(sessionInfo -> {
                if (sessionInfo == null) {
                    return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, body);
                }
                final String sessionId = sessionInfo.sessionId();
                if (sessionId == null)  {
                    final String username = sessionInfo.username();
                    final String csrfTokenFromSignedJwt = sessionInfo.csrfTokenFromSignedJwt();
                    assert username != null;
                    assert csrfTokenFromSignedJwt != null;
                    return httpResponse(csrfTokenFromSignedJwt, body);
                }

                return HttpResponse.of(sessionManager.get(sessionId).thenApply(session -> {
                    if (session == null) {
                        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, body);
                    }
                    final String csrfToken = session.csrfToken();
                    return httpResponse(csrfToken, body);
                }));
            }));
        }

        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, body);
    }

    private static HttpResponse httpResponse(@Nullable String csrfToken, HttpData body) {
        final ResponseHeadersBuilder builder =
                ResponseHeaders.builder(HttpStatus.OK)
                               .contentType(MediaType.JSON_UTF_8)
                               .set(HttpHeaderNames.CACHE_CONTROL, ServerCacheControl.DISABLED.asHeaderValue());
        if (csrfToken != null) {
            builder.set(SessionUtil.X_CSRF_TOKEN, csrfToken);
        }
        return HttpResponse.of(builder.build(), body);
    }
}
