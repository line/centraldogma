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

import static com.linecorp.centraldogma.server.internal.admin.authentication.AuthenticationUtil.requireLogin;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.SafeProjectManager;

/**
 * Annotated service object for managing users.
 */
public class UserService extends AbstractService {

    public UserService(ProjectManager projectManager,
                       CommandExecutor executor) {
        super(new SafeProjectManager(projectManager), executor);
    }

    /**
     * GET /users/me
     * Returns a login {@link User} if the user is authorized. Otherwise, {@code 401 Unauthorized} HTTP
     * response is sent.
     */
    @Get("/users/me")
    public HttpResponse usersMe() throws Exception {
        final User user = requireLogin();
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                               HttpData.of(Jackson.writeValueAsBytes(user)));
    }
}
