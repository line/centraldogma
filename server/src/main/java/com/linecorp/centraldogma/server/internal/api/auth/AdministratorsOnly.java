/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.api.auth;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.DecoratingServiceFunction;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.internal.admin.authentication.AuthenticationUtil;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;

/**
 * A decorator only to allow a request from administrator.
 */
public class AdministratorsOnly implements DecoratingServiceFunction<HttpRequest, HttpResponse> {

    @Override
    public HttpResponse serve(Service<HttpRequest, HttpResponse> delegate,
                              ServiceRequestContext ctx,
                              HttpRequest req) throws Exception {
        final User user = AuthenticationUtil.currentUser(ctx);
        if (user.isAdmin()) {
            return delegate.serve(ctx, req);
        }
        throw HttpStatusException.of(HttpStatus.FORBIDDEN);
    }
}
