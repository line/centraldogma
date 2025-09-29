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

import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.getSessionIdFromCookie;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.sessionCookieName;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ServerCacheControl;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.auth.SessionManager;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil;
import com.linecorp.centraldogma.server.internal.admin.util.RestfulJsonResponseConverter;
import com.linecorp.centraldogma.server.internal.api.AbstractService;
import com.linecorp.centraldogma.server.metadata.User;

/**
 * Annotated service object for managing users.
 */
@ResponseConverter(RestfulJsonResponseConverter.class)
public class UserService extends AbstractService {

    @Nullable
    private final SessionManager sessionManager;
    private final String sessionCookieName;

    public UserService(@Nullable SessionManager sessionManager, boolean tlsEnabled, CommandExecutor executor) {
        super(executor);
        sessionCookieName = sessionCookieName(tlsEnabled);
        this.sessionManager = sessionManager;
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
            final String sessionId = getSessionIdFromCookie(ctx, sessionCookieName);
            if (sessionId != null) {
                return HttpResponse.of(sessionManager.get(sessionId).thenApply(session -> {
                    if (session == null) {
                        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, body);
                    }
                    final ResponseHeaders headers =
                            ResponseHeaders.builder(HttpStatus.OK)
                                           .contentType(MediaType.JSON_UTF_8)
                                           .set(SessionUtil.X_CSRF_TOKEN, session.csrfToken())
                                           .set(HttpHeaderNames.CACHE_CONTROL,
                                                ServerCacheControl.DISABLED.asHeaderValue())
                                           .build();
                    return HttpResponse.of(headers, body);
                }));
            }
        }

        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, body);
    }
}
