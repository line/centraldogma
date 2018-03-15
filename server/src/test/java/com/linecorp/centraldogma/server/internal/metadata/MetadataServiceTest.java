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

import static com.linecorp.centraldogma.server.internal.metadata.PerRolePermissions.NO_PERMISSION;
import static com.linecorp.centraldogma.server.internal.metadata.PerRolePermissions.READ_ONLY;
import static com.linecorp.centraldogma.server.internal.metadata.PerRolePermissions.READ_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Rule;
import org.junit.Test;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectExistsException;
import com.linecorp.centraldogma.server.internal.storage.repository.ChangeConflictException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryExistsException;
import com.linecorp.centraldogma.testing.internal.ProjectManagerRule;

public class MetadataServiceTest {

    @Rule
    public final ProjectManagerRule rule = new ProjectManagerRule() {
        @Override
        protected void afterExecutorStarted() {
            MigrationUtil.migrate(projectManager(), executor());
            // Create a project and its metadata here.
            executor().execute(Command.createProject(author, project1)).join();
        }
    };

    private static final String project1 = "foo";
    private static final String repo1 = "apple";
    private static final String app1 = "app-1";
    private static final String app2 = "app-2";
    private static final Author author = Author.DEFAULT;
    private static final User owner = new User(Author.DEFAULT.email());
    private static final User guest = new User("guest@localhost.com");
    private static final User user1 = new User("user1@localhost.com");
    private static final User user2 = new User("user2@localhost.com");

    private static final PerRolePermissions ownerOnly =
            new PerRolePermissions(READ_WRITE, NO_PERMISSION, NO_PERMISSION);

    @Test
    public void project() throws Exception {
        final MetadataService mds = newMetadataService(rule);

        ProjectMetadata metadata;
        metadata = mds.getProject(project1).join();

        assertThatThrownBy(() -> rule.executor().execute(Command.createProject(author, project1))
                                     .join())
                .hasCauseInstanceOf(ProjectExistsException.class);

        assertThat(metadata.name()).isEqualTo(project1);
        assertThat(metadata.creation().user()).isEqualTo(author.email());
        assertThat(metadata.removal()).isNull();

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
    public void repository() throws Exception {
        final MetadataService mds = newMetadataService(rule);

        ProjectMetadata metadata;
        RepositoryMetadata repositoryMetadata;
        metadata = mds.getProject(project1).join();
        assertThat(metadata).isNotNull();

        mds.addRepo(author, project1, repo1, PerRolePermissions.ofPublic()).join();
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
    }

    @Test
    public void perRolePermissions() throws Exception {
        final MetadataService mds = newMetadataService(rule);

        ProjectMetadata metadata;
        RepositoryMetadata repositoryMetadata;
        metadata = mds.getProject(project1).join();
        assertThat(metadata).isNotNull();

        mds.addRepo(author, project1, repo1, PerRolePermissions.ofPublic()).join();

        repositoryMetadata = getRepo1(mds);
        assertThat(repositoryMetadata.perRolePermissions().owner()).containsExactly(Permission.READ,
                                                                                    Permission.WRITE);
        assertThat(repositoryMetadata.perRolePermissions().member()).containsExactly(Permission.READ,
                                                                                     Permission.WRITE);
        assertThat(repositoryMetadata.perRolePermissions().guest()).containsExactly(Permission.READ,
                                                                                    Permission.WRITE);

        mds.updatePerRolePermissions(author, project1, repo1, PerRolePermissions.ofPrivate()).join();

        repositoryMetadata = getRepo1(mds);
        assertThat(repositoryMetadata.perRolePermissions().owner()).containsExactly(Permission.READ,
                                                                                    Permission.WRITE);
        assertThat(repositoryMetadata.perRolePermissions().member()).containsExactly(Permission.READ,
                                                                                     Permission.WRITE);
        assertThat(repositoryMetadata.perRolePermissions().guest())
                .containsExactlyElementsOf(NO_PERMISSION);

        assertThat(mds.findPermissions(project1, repo1, owner).join())
                .containsExactly(Permission.READ, Permission.WRITE);
        assertThat(mds.findPermissions(project1, repo1, guest).join())
                .containsExactlyElementsOf(NO_PERMISSION);
    }

    @Test
    public void perUserPermissions() throws Exception {
        final MetadataService mds = newMetadataService(rule);

        mds.addRepo(author, project1, repo1, ownerOnly).join();

        // Not a member yet.
        assertThatThrownBy(() -> mds.addPerUserPermission(author, project1, repo1, user1, READ_ONLY).join())
                .hasCauseInstanceOf(IllegalArgumentException.class);

        // Be a member of the project.
        mds.addMember(author, project1, user1, ProjectRole.MEMBER).join();

        // A member of the project has no permission.
        assertThat(mds.findPermissions(project1, repo1, user1).join())
                .containsExactlyElementsOf(NO_PERMISSION);

        // Add 'user1' to per-user permissions table.
        mds.addPerUserPermission(author, project1, repo1, user1, READ_ONLY).join();

        // Fail due to duplicated addition.
        assertThatThrownBy(() -> mds.addPerUserPermission(author, project1, repo1, user1, READ_ONLY).join())
                .hasCauseInstanceOf(ChangeConflictException.class);

        assertThat(mds.findPermissions(project1, repo1, user1).join())
                .containsExactly(Permission.READ);

        mds.updatePerUserPermission(author, project1, repo1, user1, READ_WRITE).join();

        assertThat(mds.findPermissions(project1, repo1, user1).join())
                .containsExactly(Permission.READ, Permission.WRITE);

        mds.removePerUserPermission(author, project1, repo1, user1).join();
        assertThatThrownBy(() -> mds.removePerUserPermission(author, project1, repo1, user1).join())
                .hasCauseInstanceOf(ChangeConflictException.class);

        assertThat(mds.findPermissions(project1, repo1, user1).join())
                .containsExactlyElementsOf(NO_PERMISSION);
    }

    @Test
    public void perTokenPermissions() throws Exception {
        final MetadataService mds = newMetadataService(rule);

        mds.addRepo(author, project1, repo1, ownerOnly).join();
        mds.createToken(author, app1).join();
        final Token token = mds.findTokenByAppId(app1).join();
        assertThat(token).isNotNull();

        // Token 'app2' is not created yet.
        assertThatThrownBy(() -> mds.addToken(author, project1, app2, ProjectRole.MEMBER).join())
                .hasCauseInstanceOf(IllegalArgumentException.class);

        // Not a member yet.
        assertThatThrownBy(() -> mds.addPerTokenPermission(author, project1, repo1, app1, READ_ONLY).join())
                .hasCauseInstanceOf(IllegalArgumentException.class);

        assertThat(mds.findPermissions(project1, repo1, app1).join())
                .containsExactlyElementsOf(NO_PERMISSION);

        // Be a token of the project.
        mds.addToken(author, project1, app1, ProjectRole.MEMBER).join();
        mds.addPerTokenPermission(author, project1, repo1, app1, READ_ONLY).join();

        assertThat(mds.findPermissions(project1, repo1, app1).join())
                .containsExactly(Permission.READ);

        mds.updatePerTokenPermission(author, project1, repo1, app1, READ_WRITE).join();

        assertThat(mds.findPermissions(project1, repo1, app1).join())
                .containsExactly(Permission.READ, Permission.WRITE);

        mds.removePerTokenPermission(author, project1, repo1, app1).join();
        assertThatThrownBy(() -> mds.removePerTokenPermission(author, project1, repo1, app1).join())
                .hasCauseInstanceOf(ChangeConflictException.class);

        assertThat(mds.findPermissions(project1, repo1, app1).join())
                .containsExactlyElementsOf(NO_PERMISSION);
    }

    @Test
    public void removeMember() throws Exception {
        final MetadataService mds = newMetadataService(rule);

        mds.addRepo(author, project1, repo1, ownerOnly).join();

        mds.addMember(author, project1, user1, ProjectRole.MEMBER).join();
        mds.addMember(author, project1, user2, ProjectRole.MEMBER).join();
        mds.addPerUserPermission(author, project1, repo1, user1, READ_ONLY).join();
        mds.addPerUserPermission(author, project1, repo1, user2, READ_ONLY).join();

        assertThat(mds.getMember(project1, user1).join().id()).isNotNull();
        assertThat(mds.getMember(project1, user2).join().id()).isNotNull();

        assertThat(mds.findPermissions(project1, repo1, user1).join())
                .containsExactly(Permission.READ);

        // Remove 'user1' from the project.
        mds.removeMember(author, project1, user1).join();
        // Remove per-user permission of 'user1', too.
        assertThat(mds.findPermissions(project1, repo1, user1).join())
                .containsExactlyElementsOf(NO_PERMISSION);

        assertThat(mds.findPermissions(project1, repo1, user2).join())
                .containsExactly(Permission.READ);
    }

    @Test
    public void removeToken() throws Exception {
        final MetadataService mds = newMetadataService(rule);

        mds.addRepo(author, project1, repo1, ownerOnly).join();
        mds.createToken(author, app1).join();
        mds.createToken(author, app2).join();

        mds.addToken(author, project1, app1, ProjectRole.MEMBER).join();
        mds.addToken(author, project1, app2, ProjectRole.MEMBER).join();
        mds.addPerTokenPermission(author, project1, repo1, app1, READ_ONLY).join();
        mds.addPerTokenPermission(author, project1, repo1, app2, READ_ONLY).join();

        assertThat(mds.findPermissions(project1, repo1, app1).join())
                .containsExactly(Permission.READ);

        // Remove 'app1' from the project.
        mds.removeToken(author, project1, app1).join();
        // Remove per-token permission of 'app1', too.
        assertThat(mds.findPermissions(project1, repo1, app1).join())
                .containsExactlyElementsOf(NO_PERMISSION);

        assertThat(mds.findPermissions(project1, repo1, app2).join())
                .containsExactly(Permission.READ);
    }

    @Test
    public void destroyToken() throws Exception {
        final MetadataService mds = newMetadataService(rule);

        mds.addRepo(author, project1, repo1, ownerOnly).join();
        mds.createToken(author, app1).join();
        mds.createToken(author, app2).join();

        mds.addToken(author, project1, app1, ProjectRole.MEMBER).join();
        mds.addToken(author, project1, app2, ProjectRole.MEMBER).join();

        mds.addPerTokenPermission(author, project1, repo1, app1, READ_ONLY).join();
        mds.addPerTokenPermission(author, project1, repo1, app2, READ_ONLY).join();

        assertThat(mds.findPermissions(project1, repo1, app1).join())
                .containsExactly(Permission.READ);

        // Remove 'app1' from the system completely.
        mds.destroyToken(author, app1).join();

        // Remove per-token permission of 'app1', too.
        assertThat(mds.findPermissions(project1, repo1, app1).join())
                .containsExactlyElementsOf(NO_PERMISSION);

        assertThat(mds.findPermissions(project1, repo1, app2).join())
                .containsExactly(Permission.READ);
    }

    @Test
    public void tokenActivationAndDeactivation() throws Exception {
        final MetadataService mds = newMetadataService(rule);

        Token token;
        mds.createToken(author, app1).join();
        token = mds.getTokens().join().get(app1);
        assertThat(token).isNotNull();
        assertThat(token.creation().user()).isEqualTo(owner.id());

        mds.deactivateToken(author, app1).join();
        token = mds.getTokens().join().get(app1);
        assertThat(token.isActive()).isFalse();
        assertThat(token.deactivation().user()).isEqualTo(owner.id());

        mds.activateToken(author, app1).join();
        assertThat(mds.getTokens().join().get(app1).isActive()).isTrue();
    }

    private static RepositoryMetadata getRepo1(MetadataService mds) {
        final ProjectMetadata metadata = mds.getProject(project1).join();
        return metadata.repo(repo1);
    }

    private MetadataService newMetadataService(ProjectManagerRule rule) {
        return new MetadataService(rule.projectManager(), rule.executor());
    }

    private ProjectMetadata getProject(MetadataService mds, String projectName) {
        return mds.getProject(projectName).join();
    }
}
