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

package com.linecorp.centraldogma.server.metadata;

import static com.linecorp.centraldogma.server.metadata.RepositoryMetadata.DEFAULT_PROJECT_ROLES;
import static com.linecorp.centraldogma.server.storage.project.Project.REPO_DOGMA;
import static com.linecorp.centraldogma.server.storage.project.Project.REPO_META;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.spotify.futures.CompletableFutures;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.server.QuotaConfig;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class MetadataServiceTest {

    @SuppressWarnings("JUnitMalformedDeclaration")
    @RegisterExtension
    final ProjectManagerExtension manager = new ProjectManagerExtension() {
        @Override
        protected void afterExecutorStarted() {
            // Create a project and its metadata here.
            executor().execute(Command.createProject(author, project1)).join();
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    private static final String project1 = "foo";
    private static final String repo1 = "apple";
    private static final String repo2 = "facebook";
    private static final String app1 = "app-1";
    private static final String app2 = "app-2";
    private static final Author author = Author.DEFAULT;
    private static final User owner = new User(Author.DEFAULT.email());
    private static final User guest = new User("guest@localhost.com");
    private static final User user1 = new User("user1@localhost.com");
    private static final User user2 = new User("user2@localhost.com");

    @Test
    void project() {
        final MetadataService mds = newMetadataService(manager);

        ProjectMetadata metadata;
        metadata = mds.getProject(project1).join();

        assertThatThrownBy(() -> manager.executor().execute(Command.createProject(author, project1)).join())
                .hasCauseInstanceOf(ProjectExistsException.class);

        assertThat(metadata.name()).isEqualTo(project1);
        assertThat(metadata.creation().user()).isEqualTo(author.email());
        assertThat(metadata.removal()).isNull();
        assertThat(metadata.repos().size()).isOne();
        assertThat(metadata.repos().get(REPO_META)).isNotNull();

        // Remove a project and check whether the project is removed.
        mds.removeProject(author, project1).join();
        metadata = mds.getProject(project1).join();

        assertThat(metadata.name()).isEqualTo(project1);
        assertThat(metadata.creation().user()).isEqualTo(author.email());
        assertThat(metadata.removal()).isNotNull();
        assertThat(metadata.removal().user()).isEqualTo(author.email());

        // Restore the removed project.
        mds.restoreProject(author, project1).join();
        assertThat(mds.getProject(project1).join().removal()).isNull();
    }

    @Test
    void repository() {
        final MetadataService mds = newMetadataService(manager);

        final ProjectMetadata metadata;
        RepositoryMetadata repositoryMetadata;
        metadata = mds.getProject(project1).join();
        assertThat(metadata).isNotNull();

        mds.addRepo(author, project1, repo1, ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.WRITE))
           .join();
        assertThat(getProject(mds, project1).repos().get(repo1).name()).isEqualTo(repo1);

        // Fail due to duplicated addition.
        assertThatThrownBy(() -> mds.addRepo(author, project1, repo1).join())
                .hasCauseInstanceOf(RepositoryExistsException.class);

        // Remove a repository.
        mds.removeRepo(author, project1, repo1).join();
        assertThatThrownBy(() -> mds.removeRepo(author, project1, repo1).join())
                .hasCauseInstanceOf(ChangeConflictException.class);

        repositoryMetadata = getRepo1(mds);
        assertThat(repositoryMetadata.name()).isEqualTo(repo1);
        assertThat(repositoryMetadata.creation().user()).isEqualTo(author.email());
        assertThat(repositoryMetadata.removal()).isNotNull();
        assertThat(repositoryMetadata.removal().user()).isEqualTo(author.email());

        // Restore the removed repository.
        mds.restoreRepo(author, project1, repo1).join();

        repositoryMetadata = getRepo1(mds);
        assertThat(repositoryMetadata.name()).isEqualTo(repo1);
        assertThat(repositoryMetadata.creation().user()).isEqualTo(author.email());
        assertThat(repositoryMetadata.removal()).isNull();

        // Purge a repository.
        mds.removeRepo(author, project1, repo1).join();
        mds.purgeRepo(author, project1, repo1).join();
        assertThatThrownBy(() -> getRepo1(mds)).isInstanceOf(RepositoryNotFoundException.class);
        // Recreate the purged repository.
        mds.addRepo(author, project1, repo1, ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.WRITE))
           .join();
        assertThat(getProject(mds, project1).repos().get(repo1).name()).isEqualTo(repo1);
    }

    @Test
    void missingMetadataJsonIsAddedOnlyOnce() {
        final MetadataService mds = newMetadataService(manager);

        final ProjectMetadata metadata = mds.getProject(project1).join();
        assertThat(metadata).isNotNull();

        manager.executor().execute(Command.createRepository(author, project1, repo2)).join();
        final Builder<CompletableFuture<?>> builder = ImmutableList.builder();
        for (int i = 0; i < 10; i++) {
            builder.add(mds.getProject(project1));
        }
        // Do not throw RedundantChangeException when the same metadata are added multiple times.
        CompletableFutures.allAsList(builder.build()).join();
        assertThat(mds.getProject(project1).join().repo(repo2).creation().user()).isEqualTo(author.email());
    }

    @Test
    void repositoryProjectRoles() {
        final MetadataService mds = newMetadataService(manager);

        final ProjectMetadata metadata;
        RepositoryMetadata repositoryMetadata;
        metadata = mds.getProject(project1).join();
        assertThat(metadata).isNotNull();

        mds.addRepo(author, project1, repo1, ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.WRITE))
           .join();

        repositoryMetadata = getRepo1(mds);
        assertThat(repositoryMetadata.roles().projectRoles().member()).isSameAs(RepositoryRole.WRITE);
        assertThat(repositoryMetadata.roles().projectRoles().guest()).isEqualTo(RepositoryRole.WRITE);

        mds.updateRepositoryProjectRoles(author, project1, repo1, DEFAULT_PROJECT_ROLES).join();

        repositoryMetadata = getRepo1(mds);
        assertThat(repositoryMetadata.roles().projectRoles().member()).isSameAs(RepositoryRole.WRITE);
        assertThat(repositoryMetadata.roles().projectRoles().guest()).isNull();

        assertThat(mds.findRepositoryRole(project1, repo1, owner).join()).isSameAs(RepositoryRole.ADMIN);
        assertThat(mds.findRepositoryRole(project1, repo1, guest).join()).isNull();

        assertThatThrownBy(() -> mds.updateRepositoryProjectRoles(
                author, project1, REPO_DOGMA, ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.WRITE))
                                    .join())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Can't update role for internal repository");
        assertThatThrownBy(() -> mds.updateRepositoryProjectRoles(
                author, project1, REPO_META, ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.WRITE))
                                    .join())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Can't give a role to guest for internal repository");
    }

    @Test
    void userRepositoryRole() {
        final MetadataService mds = newMetadataService(manager);

        mds.addRepo(author, project1, repo1, ProjectRoles.of(null, null)).join();

        // Not a member yet.
        assertThatThrownBy(() -> mds.addUserRepositoryRole(author, project1, repo1, user1, RepositoryRole.READ)
                                    .join())
                .hasCauseInstanceOf(IllegalArgumentException.class);

        // Be a member of the project.
        mds.addMember(author, project1, user1, ProjectRole.MEMBER).join();

        // A member of the project has no role.
        assertThat(mds.findRepositoryRole(project1, repo1, user1).join()).isNull();

        // Add 'user1' to user repository role.
        mds.addUserRepositoryRole(author, project1, repo1, user1, RepositoryRole.READ).join();

        // Fail due to duplicated addition.
        assertThatThrownBy(() -> mds.addUserRepositoryRole(author, project1, repo1, user1, RepositoryRole.READ)
                                    .join())
                .hasCauseInstanceOf(ChangeConflictException.class);

        assertThat(mds.findRepositoryRole(project1, repo1, user1).join()).isSameAs(RepositoryRole.READ);

        mds.updateUserRepositoryRole(author, project1, repo1, user1, RepositoryRole.WRITE).join();

        assertThat(mds.findRepositoryRole(project1, repo1, user1).join()).isSameAs(RepositoryRole.WRITE);

        mds.removeUserRepositoryRole(author, project1, repo1, user1).join();
        assertThatThrownBy(() -> mds.removeUserRepositoryRole(author, project1, repo1, user1).join())
                .hasCauseInstanceOf(ChangeConflictException.class);

        assertThat(mds.findRepositoryRole(project1, repo1, user1).join()).isNull();
    }

    @Test
    void tokenRepositoryRole() {
        final MetadataService mds = newMetadataService(manager);

        mds.addRepo(author, project1, repo1, ProjectRoles.of(null, null)).join();
        mds.createToken(author, app1).join();
        final Token token = mds.findTokenByAppId(app1).join();
        assertThat(token).isNotNull();

        // Token 'app2' is not created yet.
        assertThatThrownBy(() -> mds.addToken(author, project1, app2, ProjectRole.MEMBER).join())
                .hasCauseInstanceOf(IllegalArgumentException.class);

        // Not a member yet.
        assertThatThrownBy(() -> mds.addTokenRepositoryRole(author, project1, repo1, app1, RepositoryRole.READ)
                                    .join())
                .hasCauseInstanceOf(IllegalArgumentException.class);

        assertThat(mds.findRepositoryRole(project1, repo1, app1).join()).isNull();

        // Be a token of the project.
        mds.addToken(author, project1, app1, ProjectRole.MEMBER).join();
        mds.addTokenRepositoryRole(author, project1, repo1, app1, RepositoryRole.READ).join();

        assertThat(mds.findRepositoryRole(project1, repo1, app1).join()).isSameAs(RepositoryRole.READ);

        mds.updateTokenRepositoryRole(author, project1, repo1, app1, RepositoryRole.WRITE).join();

        assertThat(mds.findRepositoryRole(project1, repo1, app1).join()).isSameAs(RepositoryRole.WRITE);

        mds.removeTokenRepositoryRole(author, project1, repo1, app1).join();
        assertThatThrownBy(() -> mds.removeTokenRepositoryRole(author, project1, repo1, app1).join())
                .hasCauseInstanceOf(ChangeConflictException.class);

        assertThat(mds.findRepositoryRole(project1, repo1, app1).join()).isNull();
    }

    @Test
    void removeMember() {
        final MetadataService mds = newMetadataService(manager);

        mds.addRepo(author, project1, repo1, ProjectRoles.of(null, null)).join();

        mds.addMember(author, project1, user1, ProjectRole.MEMBER).join();
        mds.addMember(author, project1, user2, ProjectRole.MEMBER).join();
        mds.addUserRepositoryRole(author, project1, repo1, user1, RepositoryRole.READ).join();
        mds.addUserRepositoryRole(author, project1, repo1, user2, RepositoryRole.READ).join();

        assertThat(mds.getMember(project1, user1).join().id()).isNotNull();
        assertThat(mds.getMember(project1, user2).join().id()).isNotNull();

        assertThat(mds.findRepositoryRole(project1, repo1, user1).join()).isSameAs(RepositoryRole.READ);

        // Remove 'user1' from the project.
        mds.removeMember(author, project1, user1).join();
        // Remove user repository role of 'user1', too.
        assertThat(mds.findRepositoryRole(project1, repo1, user1).join()).isNull();

        assertThat(mds.findRepositoryRole(project1, repo1, user2).join()).isSameAs(RepositoryRole.READ);
    }

    @Test
    void removeToken() {
        final MetadataService mds = newMetadataService(manager);

        mds.addRepo(author, project1, repo1, ProjectRoles.of(null, null)).join();
        mds.createToken(author, app1).join();
        mds.createToken(author, app2).join();

        mds.addToken(author, project1, app1, ProjectRole.MEMBER).join();
        mds.addToken(author, project1, app2, ProjectRole.MEMBER).join();
        mds.addTokenRepositoryRole(author, project1, repo1, app1, RepositoryRole.READ).join();
        mds.addTokenRepositoryRole(author, project1, repo1, app2, RepositoryRole.READ).join();

        assertThat(mds.findRepositoryRole(project1, repo1, app1).join()).isSameAs(RepositoryRole.READ);

        // Remove 'app1' from the project.
        mds.removeToken(author, project1, app1).join();
        // Remove token repository role of 'app1', too.
        assertThat(mds.findRepositoryRole(project1, repo1, app1).join()).isNull();

        assertThat(mds.findRepositoryRole(project1, repo1, app2).join()).isSameAs(RepositoryRole.READ);
    }

    @Test
    void destroyToken() {
        final MetadataService mds = newMetadataService(manager);

        mds.addRepo(author, project1, repo1, ProjectRoles.of(null, null)).join();
        mds.createToken(author, app1).join();
        mds.createToken(author, app2).join();

        mds.addToken(author, project1, app1, ProjectRole.MEMBER).join();
        mds.addToken(author, project1, app2, ProjectRole.MEMBER).join();

        mds.addTokenRepositoryRole(author, project1, repo1, app1, RepositoryRole.READ).join();
        mds.addTokenRepositoryRole(author, project1, repo1, app2, RepositoryRole.READ).join();

        assertThat(mds.findRepositoryRole(project1, repo1, app1).join()).isSameAs(RepositoryRole.READ);

        // Remove 'app1' from the system completely.
        mds.destroyToken(author, app1).join();
        mds.purgeToken(author, app1);

        assertThat(mds.findRepositoryRole(project1, repo1, app1).join()).isNull();

        assertThat(mds.findRepositoryRole(project1, repo1, app2).join()).isSameAs(RepositoryRole.READ);
    }

    @Test
    void tokenActivationAndDeactivation() {
        final MetadataService mds = newMetadataService(manager);

        Token token;
        mds.createToken(author, app1).join();
        token = mds.getTokens().join().get(app1);
        assertThat(token).isNotNull();
        assertThat(token.creation().user()).isEqualTo(owner.id());

        mds.deactivateToken(author, app1).join();
        token = mds.getTokens().join().get(app1);
        assertThat(token.isActive()).isFalse();
        assertThat(token.deactivation()).isNotNull();
        assertThat(token.deactivation().user()).isEqualTo(owner.id());

        mds.activateToken(author, app1).join();
        assertThat(mds.getTokens().join().get(app1).isActive()).isTrue();
    }

    @Test
    void updateWriteQuota() {
        final MetadataService mds = newMetadataService(manager);
        mds.addRepo(author, project1, repo1, ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.WRITE))
           .join();
        RepositoryMetadata repoMeta = mds.getRepo(project1, repo1).join();
        assertThat(repoMeta.writeQuota()).isNull();

        final QuotaConfig writeQuota1 = new QuotaConfig(5, 2);
        mds.updateWriteQuota(Author.SYSTEM, project1, repo1, writeQuota1).join();
        repoMeta = mds.getRepo(project1, repo1).join();
        assertThat(repoMeta.writeQuota()).isEqualTo(writeQuota1);

        final QuotaConfig writeQuota2 = new QuotaConfig(3, 1);
        mds.updateWriteQuota(Author.SYSTEM, project1, repo1, writeQuota2).join();
        repoMeta = mds.getRepo(project1, repo1).join();
        assertThat(repoMeta.writeQuota()).isEqualTo(writeQuota2);
    }

    @Test
    void updateUser() {
        final MetadataService mds = newMetadataService(manager);

        Token token;
        mds.createToken(author, app1).join();
        token = mds.getTokens().join().get(app1);
        assertThat(token).isNotNull();
        assertThat(token.isAdmin()).isFalse();

        mds.updateTokenLevel(author, app1, true).join();
        token = mds.getTokens().join().get(app1);
        assertThat(token.isAdmin()).isTrue();

        mds.updateTokenLevel(author, app1, false).join();
        token = mds.getTokens().join().get(app1);
        assertThat(token.isAdmin()).isFalse();
    }

    private static RepositoryMetadata getRepo1(MetadataService mds) {
        final ProjectMetadata metadata = mds.getProject(project1).join();
        return metadata.repo(repo1);
    }

    private static MetadataService newMetadataService(ProjectManagerExtension extension) {
        return new MetadataService(extension.projectManager(), extension.executor());
    }

    private static ProjectMetadata getProject(MetadataService mds, String projectName) {
        return mds.getProject(projectName).join();
    }
}
