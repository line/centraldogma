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

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.internal.admin.authentication.AuthenticationUtil;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * A default {@link RequestConverterFunction} of HTTP API.
 */
public final class HttpApiRequestConverter implements RequestConverterFunction {

    private final ProjectManager projectManager;

    public HttpApiRequestConverter(ProjectManager projectManager) {
        this.projectManager = requireNonNull(projectManager, "projectManager");
    }

    @Override
    public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                 Class<?> expectedResultType) throws Exception {
        if (expectedResultType == Project.class) {
            final String projectName = ctx.pathParam("projectName");
            checkArgument(!isNullOrEmpty(projectName),
                          "project name should not be null or empty.");

            // StorageNotFoundException would be thrown if there is no project.
            return projectManager.get(projectName);
        }

        if (expectedResultType == Repository.class) {
            final String projectName = ctx.pathParam("projectName");
            checkArgument(!isNullOrEmpty(projectName),
                          "project name should not be null or empty.");
            final String repositoryName = ctx.pathParam("repoName");
            checkArgument(!isNullOrEmpty(repositoryName),
                          "repository name should not be null or empty.");

            // StorageNotFoundException would be thrown if there is no project or no repository.
            return projectManager.get(projectName).repos().get(repositoryName);
        }

        if (expectedResultType == Author.class) {
            return AuthenticationUtil.currentAuthor(ctx);
        }

        if (expectedResultType == User.class) {
            return AuthenticationUtil.currentUser(ctx);
        }

        return RequestConverterFunction.fallthrough();
    }
}
