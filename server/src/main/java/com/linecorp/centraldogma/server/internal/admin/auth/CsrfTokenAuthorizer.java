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

package com.linecorp.centraldogma.server.internal.admin.auth;

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.centraldogma.internal.CsrfToken;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;
import com.linecorp.centraldogma.server.metadata.User;

/**
 * A decorator which checks whether CSRF token exists.
 * This should be used for {@link THttpService} and admin service without security.
 */
public class CsrfTokenAuthorizer extends AbstractAuthorizer {

    @Override
    public CompletionStage<Boolean> authorize(ServiceRequestContext ctx, HttpRequest req, String accessToken) {
        if (CsrfToken.ANONYMOUS.equals(accessToken)) {
            AuthUtil.setCurrentUser(ctx, User.SYSTEM_ADMIN);
            HttpApiUtil.setVerboseResponses(ctx, User.SYSTEM_ADMIN);
            return UnmodifiableFuture.completedFuture(true);
        } else {
            return UnmodifiableFuture.completedFuture(false);
        }
    }
}
