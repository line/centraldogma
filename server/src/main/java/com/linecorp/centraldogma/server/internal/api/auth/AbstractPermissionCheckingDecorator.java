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
import static com.linecorp.centraldogma.server.internal.api.auth.AbstractRoleCheckingDecorator.handleException;
import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.INTERNAL_REPOSITORY_NAME;

import java.util.Collection;
import java.util.concurrent.CompletionStage;

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
import com.linecorp.centraldogma.server.internal.metadata.Permission;

/**
 * An abstract class for checking permission of an incoming request.
 */
abstract class AbstractPermissionCheckingDecorator
        implements DecoratingServiceFunction<HttpRequest, HttpResponse> {

    @Override
    public final HttpResponse serve(Service<HttpRequest, HttpResponse> delegate,
                                    ServiceRequestContext ctx,
                                    HttpRequest req) throws Exception {

        final MetadataService mds = MetadataServiceInjector.getMetadataService(ctx);
        final User user = AuthenticationUtil.currentUser(ctx);

        final String projectName = ctx.pathParam("projectName");
        checkArgument(!isNullOrEmpty(projectName), "no project name is specified");
        final String repoName = ctx.pathParam("repoName");
        checkArgument(!isNullOrEmpty(repoName), "no repository name is specified");

        if (INTERNAL_REPOSITORY_NAME.equals(repoName)) {
            return serveInternalRepo(delegate, ctx, req, mds, user, projectName);
        } else {
            return serveUserRepo(delegate, ctx, req, mds, user, projectName, repoName);
        }
    }

    private static HttpResponse serveInternalRepo(Service<HttpRequest, HttpResponse> delegate,
                                                  ServiceRequestContext ctx, HttpRequest req,
                                                  MetadataService mds, User user,
                                                  String projectName) throws Exception {
        if (user.isAdmin()) {
            return delegate.serve(ctx, req);
        }
        // We do not manage permission for the internal repository. Actually we do not have a metadata of that.
        // So we need to check whether the current user is an 'administrator' or not when the request is
        // accessing to the internal repository.
        return HttpResponse.from(mds.findRole(projectName, user).handle((role, cause) -> {
            if (cause != null) {
                return handleException(cause);
            }
            if (!user.isAdmin()) {
                throw HttpStatusException.of(HttpStatus.FORBIDDEN);
            }
            try {
                return delegate.serve(ctx, req);
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        }));
    }

    private HttpResponse serveUserRepo(Service<HttpRequest, HttpResponse> delegate,
                                       ServiceRequestContext ctx, HttpRequest req,
                                       MetadataService mds, User user,
                                       String projectName, String repoName) throws Exception {
        final CompletionStage<Collection<Permission>> f;
        try {
            f = mds.findPermissions(projectName, repoName, user);
        } catch (Throwable cause) {
            return handleException(cause);
        }

        return HttpResponse.from(f.handle((permission, cause) -> {
            if (cause != null) {
                return handleException(cause);
            }
            if (!hasPermission(permission)) {
                throw HttpStatusException.of(HttpStatus.FORBIDDEN);
            }
            try {
                return delegate.serve(ctx, req);
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        }));
    }

    protected abstract boolean hasPermission(Collection<Permission> permission);
}
