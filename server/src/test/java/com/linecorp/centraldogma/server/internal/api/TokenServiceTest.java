/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.ContextAwareScheduledExecutorService;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.Token;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

import io.netty.util.internal.StringUtil;

class TokenServiceTest {

    @RegisterExtension
    static final ProjectManagerExtension manager = new ProjectManagerExtension();

    private static final Author adminAuthor = new Author("admin@localhost.com");
    private static final Author guestAuthor = new Author("guest@localhost.com");
    private static final User admin = new User("admin@localhost.com", User.LEVEL_ADMIN);
    private static final User guest = new User("guest@localhost.com");

    private static TokenService tokenService;

    private final ServiceRequestContext ctx = mock(ServiceRequestContext.class);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final ContextAwareScheduledExecutorService blockingExecutor =
            ContextAwareScheduledExecutorService.of(ctx, executor);

    @BeforeAll
    static void setUp() {
        tokenService = new TokenService(manager.projectManager(), manager.executor(),
                                        new MetadataService(manager.projectManager(), manager.executor()));
    }

    @Test
    void adminToken() {
        final Token token = tokenService.createToken("forAdmin1", true, null, adminAuthor, admin).join()
                                        .content();
        assertThat(token.isActive()).isTrue();
        assertThatThrownBy(() -> tokenService.createToken("forAdmin2", true, null, guestAuthor, guest)
                                             .join())
                .isInstanceOf(IllegalArgumentException.class);

        final Collection<Token> tokens = tokenService.listTokens(admin).join();
        assertThat(tokens.stream().filter(t -> !StringUtil.isNullOrEmpty(t.secret()))).hasSize(1);

        assertThatThrownBy(() -> tokenService.deleteToken(ctx, "forAdmin1", guestAuthor, guest)
                                             .join())
                .hasCauseInstanceOf(HttpResponseException.class);

        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.DELETE, "/tokens/{appId}/removed"));

        assertThat(tokenService.deleteToken(this.ctx, "forAdmin1", adminAuthor, admin).thenCompose(
                unused -> tokenService.purgeToken(ctx, "forAdmin1", adminAuthor, admin)).join()).satisfies(
                t -> {
                    assertThat(t.appId()).isEqualTo(token.appId());
                    assertThat(t.isAdmin()).isEqualTo(token.isAdmin());
                    assertThat(t.creation()).isEqualTo(token.creation());
                    assertThat(t.deactivation()).isEqualTo(token.deactivation());
                });
    }

    @Test
    void userToken() {
        final Token userToken1 = tokenService.createToken("forUser1", false, null, adminAuthor, admin)
                                             .join().content();
        final Token userToken2 = tokenService.createToken("forUser2", false, null, guestAuthor, guest)
                                             .join().content();
        assertThat(userToken1.isActive()).isTrue();
        assertThat(userToken2.isActive()).isTrue();

        final Collection<Token> tokens = tokenService.listTokens(guest).join();
        assertThat(tokens.stream().filter(token -> !StringUtil.isNullOrEmpty(token.secret())).count())
                .isEqualTo(0);

        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.DELETE, "/tokens/{appId}/removed"));

        assertThat(tokenService.deleteToken(this.ctx, "forUser1", adminAuthor, admin).thenCompose(
                unused -> tokenService.purgeToken(ctx, "forUser1", adminAuthor, admin)).join()).satisfies(t -> {
            assertThat(t.appId()).isEqualTo(userToken1.appId());
            assertThat(t.isAdmin()).isEqualTo(userToken1.isAdmin());
            assertThat(t.creation()).isEqualTo(userToken1.creation());
            assertThat(t.deactivation()).isEqualTo(userToken1.deactivation());
        });
        assertThat(tokenService.deleteToken(this.ctx, "forUser2", guestAuthor, guest).thenCompose(
                unused -> tokenService.purgeToken(ctx, "forUser2", guestAuthor, guest)).join()).satisfies(t -> {
            assertThat(t.appId()).isEqualTo(userToken2.appId());
            assertThat(t.isAdmin()).isEqualTo(userToken2.isAdmin());
            assertThat(t.creation()).isEqualTo(userToken2.creation());
            assertThat(t.deactivation()).isEqualTo(userToken2.deactivation());
        });
    }

    @Test
    void nonRandomToken() {
        final Token token = tokenService.createToken("forAdmin1", true, "appToken-secret", adminAuthor,
                                                     admin)
                                        .join().content();
        assertThat(token.isActive()).isTrue();

        final Collection<Token> tokens = tokenService.listTokens(admin).join();
        assertThat(tokens.stream().filter(t -> !StringUtil.isNullOrEmpty(t.secret()))).hasSize(1);

        assertThatThrownBy(() -> tokenService.createToken("forUser1", true, "appToken-secret", guestAuthor,
                                                          guest)
                                             .join())
                .isInstanceOf(IllegalArgumentException.class);

        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.DELETE, "/tokens/{appId}/removed"));

        tokenService.deleteToken(this.ctx, "forAdmin1", adminAuthor, admin).thenCompose(
                unused -> tokenService.purgeToken(ctx, "forAdmin1", adminAuthor, admin)).join();
    }
}
