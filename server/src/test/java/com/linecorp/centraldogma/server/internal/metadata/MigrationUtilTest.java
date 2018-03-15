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

package com.linecorp.centraldogma.server.internal.metadata;

import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.INTERNAL_PROJECT_NAME;
import static com.linecorp.centraldogma.server.internal.metadata.MigrationUtil.LEGACY_TOKEN_JSON;
import static com.linecorp.centraldogma.server.internal.metadata.MigrationUtil.LEGACY_TOKEN_REPO;
import static com.linecorp.centraldogma.server.internal.metadata.Tokens.SECRET_PREFIX;
import static com.linecorp.centraldogma.server.internal.storage.project.Project.REPO_MAIN;
import static com.linecorp.centraldogma.server.internal.storage.project.Project.REPO_META;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.junit.Rule;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.admin.authentication.LegacyToken;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.command.CreateProjectCommand;
import com.linecorp.centraldogma.server.internal.command.ForwardingCommandExecutor;
import com.linecorp.centraldogma.server.internal.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryManager;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryNotFoundException;
import com.linecorp.centraldogma.testing.internal.ProjectManagerRule;

public class MigrationUtilTest {
    @Rule
    public final ProjectManagerRule rule = new ProjectManagerRule() {
        @Override
        protected CommandExecutor newCommandExecutor(ProjectManager projectManager,
                                                     Executor worker) {
            return new LegacyProjectInitializingCommandExecutor(
                    new StandaloneCommandExecutor(projectManager, null, worker));
        }
    };

    private static final Author author = Author.SYSTEM;

    @Test
    public void migrationWithoutLegacyTokens() throws Exception {
        final ProjectManager pm = rule.projectManager();
        final CommandExecutor executor = rule.executor();

        final MetadataService mds = new MetadataService(pm, executor);

        // There is no legacy tokens.
        MigrationUtil.migrate(pm, executor);

        final Tokens tokens1 = mds.getTokens().join();
        assertThat(tokens1.appIds().isEmpty()).isTrue();
        assertThat(tokens1.secrets().isEmpty()).isTrue();
    }

    @Test
    public void migration() throws Exception {
        final ProjectManager pm = rule.projectManager();
        final CommandExecutor executor = rule.executor();

        final MetadataService mds = new MetadataService(pm, executor);

        // Create a legacy token repository.
        pm.get(INTERNAL_PROJECT_NAME).repos().create(LEGACY_TOKEN_REPO);
        createProject("legacyProject1");
        createProject("legacyProject2");
        createProject("legacyProject3");

        final LegacyToken legacyToken1 = new LegacyToken("app1", SECRET_PREFIX + "app1",
                                                         User.DEFAULT, Instant.now());
        final LegacyToken legacyToken2 = new LegacyToken("app2", SECRET_PREFIX + "app2",
                                                         User.DEFAULT, Instant.now());
        final Map<String, LegacyToken> legacyTokens =
                ImmutableMap.of(SECRET_PREFIX + "app1", legacyToken1,
                                SECRET_PREFIX + "app2", legacyToken2);
        final Change<?> change = Change.ofJsonUpsert(LEGACY_TOKEN_JSON,
                                                     Jackson.valueToTree(legacyTokens));
        executor.execute(Command.push(author, INTERNAL_PROJECT_NAME, LEGACY_TOKEN_REPO,
                                      Revision.HEAD, "", "", Markup.PLAINTEXT,
                                      change)).join();
        for (int i = 0; i < 2; i++) {
            // Try to migrate again at the second time. The result should be the same as before.
            MigrationUtil.migrate(pm, executor);

            final Tokens tokens2 = mds.getTokens().join();
            assertThat(tokens2.appIds().size()).isEqualTo(2);
            assertThat(tokens2.appIds().get("app1").secret()).isEqualTo(legacyToken1.secret());
            assertThat(tokens2.appIds().get("app2").secret()).isEqualTo(legacyToken2.secret());

            final List<ProjectMetadata> metadataList = ImmutableList.of(
                    mds.getProject("legacyProject1").join(),
                    mds.getProject("legacyProject2").join(),
                    mds.getProject("legacyProject3").join());

            for (final ProjectMetadata m : metadataList) {
                assertThat(m.tokens().size()).isEqualTo(2);

                // Every token has to be registered to every project with member role.
                assertThat(m.tokens().get("app1").role()).isEqualTo(ProjectRole.MEMBER);
                assertThat(m.tokens().get("app2").role()).isEqualTo(ProjectRole.MEMBER);

                // Every repository is "public", so everyone has read/write permission to the repository.
                assertThat(m.repo(REPO_MAIN).perRolePermissions().owner())
                        .containsExactly(Permission.READ, Permission.WRITE);
                assertThat(m.repo("oneMoreThing").perRolePermissions().owner())
                        .containsExactly(Permission.READ, Permission.WRITE);

                assertThat(m.repo(REPO_MAIN).perRolePermissions().member())
                        .containsExactly(Permission.READ, Permission.WRITE);
                assertThat(m.repo("oneMoreThing").perRolePermissions().member())
                        .containsExactly(Permission.READ, Permission.WRITE);

                assertThat(m.repo(REPO_MAIN).perRolePermissions().guest())
                        .containsExactly(Permission.READ, Permission.WRITE);
                assertThat(m.repo("oneMoreThing").perRolePermissions().guest())
                        .containsExactly(Permission.READ, Permission.WRITE);

                // Do not add "meta" repository to the metadata.
                assertThatThrownBy(() -> m.repo(REPO_META))
                        .isInstanceOf(RepositoryNotFoundException.class);
            }
        }
    }

    private void createProject(String projectName) {
        final RepositoryManager manager = rule.projectManager().create(projectName).repos();
        manager.create(REPO_META);
        manager.create(REPO_MAIN);
        manager.create("oneMoreThing");
    }

    static class LegacyProjectInitializingCommandExecutor extends ForwardingCommandExecutor {

        LegacyProjectInitializingCommandExecutor(CommandExecutor delegate) {
            super(delegate);
        }

        @Override
        public <T> CompletableFuture<T> execute(Command<T> command) {
            if (!(command instanceof CreateProjectCommand)) {
                return super.execute(command);
            }

            final CreateProjectCommand c = (CreateProjectCommand) command;
            final String projectName = c.projectName();
            final long creationTimeMillis = c.timestamp();
            final Author author = c.author();

            // Do not generate sample files because they are not necessary for the migration test.
            final CompletableFuture<Void> f = delegate().execute(c);
            return f.thenCompose(unused -> delegate().execute(
                    Command.createRepository(creationTimeMillis, author, projectName, REPO_META)))
                    .thenCompose(unused -> delegate().execute(
                            Command.createRepository(creationTimeMillis, author, projectName, REPO_MAIN)))
                    .thenApply(unused -> null);
        }
    }
}
