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
package com.linecorp.centraldogma.server.internal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collection;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.metadata.MetadataService;
import com.linecorp.centraldogma.server.internal.metadata.MigrationUtil;
import com.linecorp.centraldogma.server.internal.metadata.Token;
import com.linecorp.centraldogma.testing.internal.ProjectManagerRule;

import io.netty.util.internal.StringUtil;

public class TokenServiceTest {

    @ClassRule
    public static final ProjectManagerRule rule = new ProjectManagerRule() {
        @Override
        protected void afterExecutorStarted() {
            MigrationUtil.migrate(projectManager(), executor());
        }
    };

    private static final Author adminAuthor = new Author("admin@localhost.com");
    private static final Author guestAuthor = new Author("guest@localhost.com");
    private static final User admin = new User("admin@localhost.com", User.LEVEL_ADMIN);
    private static final User guest = new User("guest@localhost.com");

    private static TokenService tokenService;

    @BeforeClass
    public static void beforeClass() {
        tokenService = new TokenService(rule.projectManager(), rule.executor(),
                                        new MetadataService(rule.projectManager(), rule.executor()));
    }

    @Test
    public void adminToken() {
        assertThat(tokenService.createToken("forAdmin1", true, adminAuthor, admin)
                               .join().object().isActive()).isTrue();
        assertThatThrownBy(() -> tokenService.createToken("forAdmin2", true, guestAuthor, guest)
                                             .join())
                .isInstanceOf(IllegalArgumentException.class);

        final Collection<Token> tokens = tokenService.listTokens(admin).join();
        assertThat(tokens.stream().filter(token -> !StringUtil.isNullOrEmpty(token.secret())).count())
                .isEqualTo(1);

        assertThatThrownBy(() -> tokenService.deleteToken("forAdmin1", guestAuthor, guest)
                                             .join())
                .hasCauseInstanceOf(HttpStatusException.class);

        assertThat(tokenService.deleteToken("forAdmin1", adminAuthor, admin).join());
    }

    @Test
    public void userToken() {
        assertThat(tokenService.createToken("forUser1", false, adminAuthor, admin)
                               .join().object().isActive()).isTrue();
        assertThat(tokenService.createToken("forUser2", false, guestAuthor, guest)
                               .join().object().isActive()).isTrue();

        final Collection<Token> tokens = tokenService.listTokens(guest).join();
        assertThat(tokens.stream().filter(token -> !StringUtil.isNullOrEmpty(token.secret())).count())
                .isEqualTo(0);

        assertThat(tokenService.deleteToken("forUser1", adminAuthor, admin).join());
        assertThat(tokenService.deleteToken("forUser2", guestAuthor, guest).join());
    }
}
