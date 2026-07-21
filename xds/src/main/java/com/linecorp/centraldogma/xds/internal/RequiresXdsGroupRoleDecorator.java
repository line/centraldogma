/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.xds.internal;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.internal.Util.PROJECT_AND_REPO_NAME_PATTERN;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.errorResponse;
import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.project.Project;

/**
 * A {@link Decorator} that enforces the specified {@link RepositoryRole} on the xDS group identified
 * by the {@code {group}} path variable. Performs the following checks in order:
 * <ol>
 *   <li>Group name matches the allowed pattern → 400 if not</li>
 *   <li>Group repository exists in the xDS project → 404 if not</li>
 *   <li>Request is authenticated → 401 if not</li>
 *   <li>User is a system administrator, or has the required role on the group → 403 if not</li>
 * </ol>
 */
public final class RequiresXdsGroupRoleDecorator extends SimpleDecoratingHttpService {

    private final Project xdsProject;
    private final RepositoryRole requiredRole;

    RequiresXdsGroupRoleDecorator(HttpService delegate, Project xdsProject,
                                  RepositoryRole requiredRole) {
        super(delegate);
        this.xdsProject = requireNonNull(xdsProject, "xdsProject");
        this.requiredRole = requireNonNull(requiredRole, "requiredRole");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final User user = AuthUtil.currentUser(ctx);
        if (user == null) {
            return errorResponse(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        final String group = ctx.pathParam("group");
        if (isNullOrEmpty(group)) {
            return errorResponse(HttpStatus.BAD_REQUEST, "group path variable is missing");
        }
        if (!PROJECT_AND_REPO_NAME_PATTERN.matcher(group).matches()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Invalid group: " + group);
        }
        if (!xdsProject.repos().exists(group)) {
            return errorResponse(HttpStatus.NOT_FOUND, "Group not found: " + group);
        }

        if (user.isSystemAdmin()) {
            return unwrap().serve(ctx, req);
        }

        final ProjectMetadata metadata = xdsProject.metadata();
        // @xds is not the internal dogma project, so metadata is always initialized — never null.
        assert metadata != null;
        final RepositoryRole role =
                MetadataService.findRepositoryRole(metadata, group, user);
        if (role != null && role.has(requiredRole)) {
            return unwrap().serve(ctx, req);
        }
        return errorResponse(HttpStatus.FORBIDDEN, "No " + requiredRole +
                                                   " permission for group: " + group);
    }

    /**
     * A {@link DecoratorFactoryFunction} that creates a {@link RequiresXdsGroupRoleDecorator}.
     */
    public static final class RequiresXdsGroupRoleDecoratorFactory
            implements DecoratorFactoryFunction<RequiresXdsGroupRole> {

        private final Project xdsProject;

        public RequiresXdsGroupRoleDecoratorFactory(Project xdsProject) {
            this.xdsProject = requireNonNull(xdsProject, "xdsProject");
        }

        @Override
        public Function<? super HttpService, ? extends HttpService>
        newDecorator(RequiresXdsGroupRole parameter) {
            return delegate -> new RequiresXdsGroupRoleDecorator(
                    delegate, xdsProject, parameter.value());
        }
    }
}
