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

import static com.linecorp.centraldogma.server.internal.admin.authentication.AuthenticationUtil.CURRENT_USER_KEY;
import static com.linecorp.centraldogma.server.internal.admin.decorator.RoleResolvingDecorator.ROLE_MAP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.DefaultAttributeMap;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.admin.model.ProjectRole;
import com.linecorp.centraldogma.server.internal.admin.service.MetadataService;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;

public class DecoratorTest {

    private static final Map<String, ProjectRole> roleMap = ImmutableMap.of("A", ProjectRole.OWNER,
                                                                            "B", ProjectRole.MEMBER);

    private static final Service<HttpRequest, HttpResponse> delegate =
            (ctx, req) -> HttpResponse.of(HttpStatus.OK);

    @Test
    public void testRoleResolvingDecorator() throws Exception {
        final MetadataService mds = mock(MetadataService.class);

        when(mds.findRoles(any(User.class))).thenReturn(CompletableFuture.completedFuture(roleMap));

        final RoleResolvingDecorator decorator =
                new RoleResolvingDecorator((ctx, req) -> HttpResponse.of(HttpStatus.OK), mds);

        final DefaultAttributeMap attrs = new DefaultAttributeMap();
        attrs.attr(CURRENT_USER_KEY).set(User.DEFAULT);

        final ServiceRequestContext ctx = spy(mock(ServiceRequestContext.class));
        final DefaultEventLoop eventLoop = new DefaultEventLoop();

        when(ctx.contextAwareEventLoop()).thenReturn(eventLoop);
        when(ctx.attr(CURRENT_USER_KEY)).thenReturn(attrs.attr(CURRENT_USER_KEY));
        when(ctx.attr(ROLE_MAP)).thenReturn(attrs.attr(ROLE_MAP));

        try (SafeCloseable ignore = RequestContext.push(ctx)) {
            decorator.serve(ctx, null);

            await().until(() -> eventLoop.pendingTasks() == 0);

            final Function<String, ProjectRole> func = attrs.attr(ROLE_MAP).get();
            assertThat(func).isNotNull();

            assertThat(func.apply("A")).isEqualTo(ProjectRole.OWNER);
            assertThat(func.apply("B")).isEqualTo(ProjectRole.MEMBER);
        }
    }

    @Test
    public void testProjectAccessController() throws Exception {
        final EventLoop ev = new DefaultEventLoop();

        try {
            final ServiceRequestContext ctx = spy(mock(ServiceRequestContext.class));
            when(ctx.eventLoop()).thenReturn(ev);

            final DefaultAttributeMap attrs = new DefaultAttributeMap();
            attrs.attr(ROLE_MAP).set(roleMap::get);
            when(ctx.attr(CURRENT_USER_KEY)).thenReturn(attrs.attr(CURRENT_USER_KEY));
            when(ctx.attr(ROLE_MAP)).thenReturn(attrs.attr(ROLE_MAP));

            try (SafeCloseable ignore = RequestContext.push(ctx)) {

                attrs.attr(CURRENT_USER_KEY).set(User.DEFAULT);

                when(ctx.pathParam("projectName")).thenReturn("A");
                assertThat(new ProjectMembersOnly().serve(delegate, ctx, null).aggregate().join().status())
                        .isEqualTo(HttpStatus.OK);
                assertThat(new ProjectOwnersOnly().serve(delegate, ctx, null).aggregate().join().status())
                        .isEqualTo(HttpStatus.OK);

                when(ctx.pathParam("projectName")).thenReturn("B");
                assertThat(new ProjectMembersOnly().serve(delegate, ctx, null).aggregate().join().status())
                        .isEqualTo(HttpStatus.OK);
                assertThatThrownBy(() -> new ProjectOwnersOnly().serve(delegate, ctx, null))
                        .isInstanceOf(HttpStatusException.class)
                        .satisfies(cause -> {
                            assertThat(((HttpStatusException) cause).httpStatus())
                                    .isEqualTo(HttpStatus.UNAUTHORIZED);
                        });

                attrs.attr(CURRENT_USER_KEY).set(User.ADMIN);

                assertThat(new AdministratorsOnly().serve(delegate, ctx, null).aggregate().join().status())
                        .isEqualTo(HttpStatus.OK);
                assertThat(new ProjectMembersOnly().serve(delegate, ctx, null).aggregate().join().status())
                        .isEqualTo(HttpStatus.OK);
                assertThat(new ProjectOwnersOnly().serve(delegate, ctx, null).aggregate().join().status())
                        .isEqualTo(HttpStatus.OK);
            }
        } finally {
            ev.shutdownGracefully();
        }
    }
}
