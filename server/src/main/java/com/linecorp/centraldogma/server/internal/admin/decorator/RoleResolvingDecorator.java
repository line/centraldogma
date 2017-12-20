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

package com.linecorp.centraldogma.server.internal.admin.decorator;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;
import com.linecorp.centraldogma.server.internal.admin.authentication.AuthenticationUtil;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.admin.authentication.UserWithToken;
import com.linecorp.centraldogma.server.internal.admin.model.ProjectRole;
import com.linecorp.centraldogma.server.internal.admin.service.MetadataService;

import io.netty.util.AttributeKey;

/**
 * Resolves every {@link ProjectRole} of the current user.
 */
public class RoleResolvingDecorator extends SimpleDecoratingService<HttpRequest, HttpResponse> {

    public static Function<Service<HttpRequest, HttpResponse>,
            RoleResolvingDecorator> newDecorator(MetadataService mds) {
        requireNonNull(mds, "mds");
        return service -> new RoleResolvingDecorator(service, mds);
    }

    static final AttributeKey<Function<String, ProjectRole>> ROLE_MAP =
            AttributeKey.valueOf(RoleResolvingDecorator.class, "ROLE_MAP");

    private final MetadataService mds;

    public RoleResolvingDecorator(Service<HttpRequest, HttpResponse> delegate,
                                  MetadataService mds) {
        super(delegate);
        this.mds = requireNonNull(mds, "mds");
    }

    /**
     * Resolves all {@link ProjectRole}s of the current user and puts them into {@link RequestContext}
     * attributes.
     */
    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {

        final User user = AuthenticationUtil.currentUser(ctx);

        final CompletionStage<Map<String, ProjectRole>> future;
        if (user instanceof UserWithToken) {
            future = mds.findRole(((UserWithToken) user).secret());
        } else {
            future = mds.findRoles(user);
        }

        return HttpResponse.from(future.thenApplyAsync(map -> {
            try {
                ctx.attr(ROLE_MAP).set(map::get);
                return delegate().serve(ctx, req);
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        }, ctx.contextAwareEventLoop()));
    }
}
