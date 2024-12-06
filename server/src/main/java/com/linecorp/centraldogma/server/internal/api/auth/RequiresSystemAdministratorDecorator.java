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

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;
import com.linecorp.centraldogma.server.metadata.User;

/**
 * A {@link Decorator} to allow a request from a system administrator only.
 */
public final class RequiresSystemAdministratorDecorator extends SimpleDecoratingHttpService {

    RequiresSystemAdministratorDecorator(HttpService delegate) {
        super(delegate);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final User user = AuthUtil.currentUser(ctx);
        if (user.isSystemAdmin()) {
            return unwrap().serve(ctx, req);
        }
        return HttpApiUtil.throwResponse(
                ctx, HttpStatus.FORBIDDEN,
                "You must be a system administrator to perform this operation.");
    }

    /**
     * A {@link DecoratorFactoryFunction} which creates a {@link RequiresSystemAdministratorDecorator}.
     */
    public static final class RequiresSystemAdministratorDecoratorFactory
            implements DecoratorFactoryFunction<RequiresSystemAdministrator> {
        @Override
        public Function<? super HttpService, ? extends HttpService>
        newDecorator(RequiresSystemAdministrator parameter) {
            return RequiresSystemAdministratorDecorator::new;
        }
    }
}
