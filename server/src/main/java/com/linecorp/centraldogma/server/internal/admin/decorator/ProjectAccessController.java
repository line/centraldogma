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

package com.linecorp.centraldogma.server.internal.admin.decorator;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;

import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.DecoratingServiceFunction;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.internal.admin.authentication.AuthenticationUtil;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.admin.model.ProjectRole;

/**
 * A decorator only to allow a request with a proper user role. It will be work only if the request has a
 * {@code projectName} path variable.
 */
abstract class ProjectAccessController implements DecoratingServiceFunction<HttpRequest, HttpResponse> {

    @Override
    public HttpResponse serve(Service<HttpRequest, HttpResponse> delegate,
                              ServiceRequestContext ctx,
                              HttpRequest req) throws Exception {
        final String projectName = ctx.pathParam("projectName");
        checkArgument(!isNullOrEmpty(projectName),
                      "No project name is specified");

        final Function<String, ProjectRole> map = ctx.attr(RoleResolvingDecorator.ROLE_MAP).get();
        final ProjectRole projectRole = map != null ? map.apply(projectName) : null;
        final User user = AuthenticationUtil.currentUser(ctx);
        if (!isAllowedRole(user, projectRole)) {
            throw HttpStatusException.of(HttpStatus.UNAUTHORIZED);
        }

        return delegate.serve(ctx, req);
    }

    protected abstract boolean isAllowedRole(User user, @Nullable ProjectRole projectRole);
}
