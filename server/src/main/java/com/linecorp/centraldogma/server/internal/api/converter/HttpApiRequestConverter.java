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

package com.linecorp.centraldogma.server.internal.api.converter;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.ParameterizedType;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * A default {@link RequestConverterFunction} of HTTP API.
 */
public final class HttpApiRequestConverter implements RequestConverterFunction {

    private final ProjectManager projectManager;

    public HttpApiRequestConverter(ProjectManager projectManager) {
        this.projectManager = requireNonNull(projectManager, "projectManager");
    }

    @Override
    public Object convertRequest(
            ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
            @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {

        if (expectedResultType == Project.class) {
            final String projectName = ctx.pathParam("projectName");
            checkArgument(!isNullOrEmpty(projectName),
                          "project name should not be null or empty.");

            // ProjectNotFoundException would be thrown if there is no project.
            return projectManager.get(projectName);
        }

        if (expectedResultType == Repository.class) {
            final String projectName = ctx.pathParam("projectName");
            checkArgument(!isNullOrEmpty(projectName),
                          "project name should not be null or empty.");
            final String repositoryName = ctx.pathParam("repoName");
            checkArgument(!isNullOrEmpty(repositoryName),
                          "repository name should not be null or empty.");

            if (Project.REPO_DOGMA.equals(repositoryName) &&
                !AuthUtil.currentUser(ctx).isAdmin()) {
                return HttpApiUtil.throwResponse(
                        ctx, HttpStatus.FORBIDDEN,
                        "Repository '%s/%s' can be accessed only by an administrator.",
                        projectName, Project.REPO_DOGMA);
            }
            // RepositoryNotFoundException would be thrown if there is no project or no repository.
            return projectManager.get(projectName).repos().get(repositoryName);
        }

        if (expectedResultType == Author.class) {
            return AuthUtil.currentAuthor(ctx);
        }

        if (expectedResultType == User.class) {
            return AuthUtil.currentUser(ctx);
        }

        return RequestConverterFunction.fallthrough();
    }
}
