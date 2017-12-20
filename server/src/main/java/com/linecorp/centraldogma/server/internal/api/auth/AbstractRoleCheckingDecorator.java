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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.DecoratingServiceFunction;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.internal.admin.authentication.AuthenticationUtil;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.metadata.MetadataService;
import com.linecorp.centraldogma.server.internal.metadata.MetadataServiceInjector;
import com.linecorp.centraldogma.server.internal.metadata.ProjectRole;
import com.linecorp.centraldogma.server.internal.storage.StorageNotFoundException;

/**
 * An abstract class for checking the project role of the user sent a request.
 */
abstract class AbstractRoleCheckingDecorator
        implements DecoratingServiceFunction<HttpRequest, HttpResponse> {

    @Override
    public final HttpResponse serve(Service<HttpRequest, HttpResponse> delegate, ServiceRequestContext ctx,
                                    HttpRequest req) throws Exception {

        final MetadataService mds = MetadataServiceInjector.getMetadataService(ctx);
        final User user = AuthenticationUtil.currentUser(ctx);

        final String projectName = ctx.pathParam("projectName");
        checkArgument(!isNullOrEmpty(projectName), "no project name is specified");

        try {
            return HttpResponse.from(mds.findRole(projectName, user).handle((role, cause) -> {
                if (cause != null) {
                    return handleException(cause);
                }
                if (!isAccessAllowed(ctx, req, user, role)) {
                    throw HttpStatusException.of(HttpStatus.FORBIDDEN);
                }
                try {
                    return delegate.serve(ctx, req);
                } catch (Exception e) {
                    return Exceptions.throwUnsafely(e);
                }
            }));
        } catch (Throwable cause) {
            return handleException(cause);
        }
    }

    static HttpResponse handleException(Throwable cause) {
        cause = Exceptions.peel(cause);
        if (cause instanceof StorageNotFoundException) {
            return HttpResponse.of(HttpStatus.NOT_FOUND);
        } else {
            return Exceptions.throwUnsafely(cause);
        }
    }

    protected abstract boolean isAccessAllowed(ServiceRequestContext ctx, HttpRequest req,
                                               User user, ProjectRole role);
}
