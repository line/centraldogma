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
package com.linecorp.centraldogma.server.internal.admin.service;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.AuthTokenExtractors;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;

public class DefaultLogoutService extends AbstractHttpService {

    private final CommandExecutor executor;

    public DefaultLogoutService(CommandExecutor executor) {
        this.executor = requireNonNull(executor, "executor");
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.from(
                req.aggregate()
                   .thenApply(msg -> AuthTokenExtractors.OAUTH2.apply(msg.headers()))
                   .thenCompose(token -> {
                       final String sessionId = token.accessToken();
                       return executor.execute(Command.removeSession(sessionId));
                   })
                   .thenApply(unused -> HttpResponse.of(HttpStatus.OK))
                   .exceptionally(cause -> HttpApiUtil.newResponse(HttpStatus.INTERNAL_SERVER_ERROR, cause)));
    }
}
