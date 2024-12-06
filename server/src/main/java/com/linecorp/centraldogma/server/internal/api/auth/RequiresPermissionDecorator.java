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
import static com.linecorp.centraldogma.server.internal.api.auth.RequiresRoleDecorator.handleException;
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.internal.api.GitHttpService;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.Permission;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.project.Project;

/**
 * A {@link Decorator} to allow a request from a user who has the specified {@link Permission}.
 */
public final class RequiresPermissionDecorator extends SimpleDecoratingHttpService {

    private final MetadataService mds;

    private final Permission requiredPermission;
    @Nullable
    private final String projectName;
    @Nullable
    private final String repoName;

    RequiresPermissionDecorator(HttpService delegate, MetadataService mds, Permission requiredPermission,
                                @Nullable String projectName,
                                @Nullable String repoName) {
        super(delegate);
        this.mds = requireNonNull(mds, "mds");
        this.requiredPermission = requireNonNull(requiredPermission, "requiredPermission");
        this.projectName = projectName;
        this.repoName = repoName;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final User user = AuthUtil.currentUser(ctx);

        String projectName = this.projectName;
        if (projectName == null) {
            projectName = ctx.pathParam("projectName");
        }
        checkArgument(!isNullOrEmpty(projectName), "no project name is specified");
        String repoName = this.repoName;
        if (repoName == null) {
            repoName = ctx.pathParam("repoName");
        }
        checkArgument(!isNullOrEmpty(repoName), "no repository name is specified");

        if (Project.REPO_DOGMA.equals(repoName)) {
            if (!user.isSystemAdmin()) {
                return throwForbiddenResponse(ctx, projectName, repoName);
            }
            return unwrap().serve(ctx, req);
        }
        return serveUserRepo(ctx, req, user, projectName, maybeRemoveGitSuffix(repoName));
    }

    private static HttpResponse throwForbiddenResponse(ServiceRequestContext ctx, String projectName,
                                                       String repoName) {
        return HttpApiUtil.throwResponse(ctx, HttpStatus.FORBIDDEN,
                                         "Repository '%s/%s' can be accessed only by a system administrator.",
                                         projectName, repoName);
    }

    /**
     * Removes the trailing ".git" suffix from the repository name if it exists. This is added for
     * GitHttpService. See {@link GitHttpService}.
     */
    private static String maybeRemoveGitSuffix(String repoName) {
        if (repoName.length() >= 5 && repoName.endsWith(".git")) {
            repoName = repoName.substring(0, repoName.length() - 4);
        }
        return repoName;
    }

    private HttpResponse serveUserRepo(ServiceRequestContext ctx, HttpRequest req,
                                       User user, String projectName, String repoName) throws Exception {
        final CompletionStage<Collection<Permission>> f;
        try {
            f = mds.findPermissions(projectName, repoName, user);
        } catch (Throwable cause) {
            return handleException(ctx, cause);
        }

        return HttpResponse.of(f.handle((permission, cause) -> {
            if (cause != null) {
                return handleException(ctx, cause);
            }
            if (!permission.contains(requiredPermission)) {
                return HttpApiUtil.throwResponse(
                        ctx, HttpStatus.FORBIDDEN,
                        "You must have %s permission for repository '%s/%s'.",
                        requiredPermission, projectName, repoName);
            }
            try {
                return unwrap().serve(ctx, req);
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        }));
    }

    /**
     * A {@link DecoratorFactoryFunction} which creates a {@link RequiresPermissionDecorator} with a read
     * {@link Permission}.
     */
    public static final class RequiresReadPermissionDecoratorFactory
            implements DecoratorFactoryFunction<RequiresReadPermission> {

        private final MetadataService mds;

        public RequiresReadPermissionDecoratorFactory(MetadataService mds) {
            this.mds = requireNonNull(mds, "mds");
        }

        @Override
        public Function<? super HttpService, ? extends HttpService>
        newDecorator(RequiresReadPermission parameter) {
            return delegate -> new RequiresPermissionDecorator(delegate, mds, Permission.READ,
                                                               Strings.emptyToNull(parameter.project()),
                                                               Strings.emptyToNull(parameter.repository()));
        }
    }

    /**
     * A {@link DecoratorFactoryFunction} which creates a {@link RequiresPermissionDecorator} with a write
     * {@link Permission}.
     */
    public static final class RequiresWritePermissionDecoratorFactory
            implements DecoratorFactoryFunction<RequiresWritePermission> {

        private final MetadataService mds;

        public RequiresWritePermissionDecoratorFactory(MetadataService mds) {
            this.mds = requireNonNull(mds, "mds");
        }

        @Override
        public Function<? super HttpService, ? extends HttpService>
        newDecorator(RequiresWritePermission parameter) {
            return delegate -> new RequiresPermissionDecorator(delegate, mds, Permission.WRITE,
                                                               Strings.emptyToNull(parameter.project()),
                                                               Strings.emptyToNull(parameter.repository()));
        }
    }
}
