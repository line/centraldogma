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
import static org.awaitility.Awaitility.await;

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
import com.linecorp.centraldogma.common.Revision;
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
    private static final Author author = Author.DEFAULT;
    private static final User owner = new User(Author.DEFAULT.email());
    private static final User guest = new User("guest@localhost.com");
    private static final User user1 = new User("user1@localhost.com");
    private static final User user2 = new User("user2@localhost.com");
    private static final String app1 = "app-1";
    private static final String app2 = "app-2";
    private static final String cert1 = "cert-1";
    private static final String cert2 = "cert-2";
    private static final String certificateId1 = "certificate/id/1";
    private static final String certificateId2 = "certificate/id/2";
    private static final Token appToken1 = new Token(app1, "secret", false, true, UserAndTimestamp.of(author));
    private static final Token appToken2 = new Token(app2, "secret", false, true, UserAndTimestamp.of(author));
    private static final CertificateAppIdentity certificate1 =
            new CertificateAppIdentity(cert1, certificateId1, false, true, UserAndTimestamp.of(author));
    private static final CertificateAppIdentity certificate2 =
            new CertificateAppIdentity(cert2, certificateId2, false, true, UserAndTimestamp.of(author));

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
        assertThat(metadata.repos().size()).isZero();

        // Remove a project and check whether the project is removed.
        mds.removeProject(author, project1).join();
        await().untilAsserted(() -> assertThat(mds.getProject(project1).join().removal()).isNotNull());
        metadata = mds.getProject(project1).join();

        assertThat(metadata.name()).isEqualTo(project1);
        assertThat(metadata.creation().user()).isEqualTo(author.email());
        assertThat(metadata.removal().user()).isEqualTo(author.email());

        // Restore the removed project.
        mds.restoreProject(author, project1).join();
        await().untilAsserted(() -> assertThat(mds.getProject(project1).join().removal()).isNull());
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
        await().untilAsserted(() -> assertThat(getProject(mds, project1).repos().get(repo1).name())
                .isEqualTo(repo1));

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
        await().untilAsserted(() -> assertThat(getRepo1(mds).removal()).isNull());

        repositoryMetadata = getRepo1(mds);
        assertThat(repositoryMetadata.name()).isEqualTo(repo1);
        assertThat(repositoryMetadata.creation().user()).isEqualTo(author.email());

        // Purge a repository.
        mds.removeRepo(author, project1, repo1).join();
        mds.purgeRepo(author, project1, repo1).join();
        await().untilAsserted(() -> assertThatThrownBy(() -> getRepo1(mds))
                .isInstanceOf(RepositoryNotFoundException.class));
        // Recreate the purged repository.
        mds.addRepo(author, project1, repo1, ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.WRITE))
           .join();
        await().untilAsserted(() -> assertThat(getProject(mds, project1).repos().get(repo1).name())
                .isEqualTo(repo1));
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
        metadata = mds.getProject(project1).join();
        assertThat(metadata).isNotNull();

        mds.addRepo(author, project1, repo1, ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.WRITE))
           .join();
        await().until(() -> getRepo1(mds) != null);
        final RepositoryMetadata repositoryMetadata = getRepo1(mds);
        assertThat(repositoryMetadata.roles().projectRoles().member()).isSameAs(RepositoryRole.WRITE);
        // WRITE permission is not allowed for GUEST so it is automatically lowered to READ.
        assertThat(repositoryMetadata.roles().projectRoles().guest()).isEqualTo(RepositoryRole.READ);

        final Revision revision =
                mds.updateRepositoryProjectRoles(author, project1, repo1, DEFAULT_PROJECT_ROLES).join();

        await().untilAsserted(() -> assertThat(getRepo1(mds).roles().projectRoles().guest()).isNull());
        assertThat(getRepo1(mds).roles().projectRoles().member()).isSameAs(RepositoryRole.WRITE);

        assertThat(mds.findRepositoryRole(project1, repo1, owner).join()).isSameAs(RepositoryRole.ADMIN);
        assertThat(mds.findRepositoryRole(project1, repo1, guest).join()).isNull();

        // Updating the same role is ok.
        assertThat(mds.updateRepositoryProjectRoles(
                author, project1, repo1, DEFAULT_PROJECT_ROLES).join())
                .isEqualTo(revision);

        assertThatThrownBy(() -> mds.updateRepositoryProjectRoles(
                author, project1, REPO_DOGMA, ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.WRITE))
                                    .join())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Can't update role for internal repository");
        assertThatThrownBy(() -> mds.updateRepositoryProjectRoles(
                author, project1, REPO_META, ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.WRITE))
                                    .join())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Can't update role for internal repository: meta");
    }

    @Test
    void userRepositoryRole() {
        final MetadataService mds = newMetadataService(manager);

        final RepositoryMetadata repositoryMetadata =
                RepositoryMetadata.of(repo1, Roles.EMPTY, UserAndTimestamp.of(author));

        mds.addRepo(author, project1, repo1, repositoryMetadata).join();
        await().until(() -> getRepo1(mds) != null);

        // invalid repo.
        assertThatThrownBy(() -> mds.addUserRepositoryRole(
                author, project1, "invalid-repo", user1, RepositoryRole.READ).join())
                .hasCauseInstanceOf(RepositoryNotFoundException.class);

        // Not a member of the project yet, so the role should be null.
        assertThat(mds.findRepositoryRole(project1, repo1, user1).join()).isNull();

        // Add user repository role without registering to the project first.
        final Revision revision = mds.addUserRepositoryRole(author, project1, repo1, user1, RepositoryRole.READ)
                                     .join();
        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, user1).join())
                .isSameAs(RepositoryRole.READ));

        // Fail due to duplicated addition.
        assertThatThrownBy(() -> mds.addUserRepositoryRole(author, project1, repo1, user1, RepositoryRole.READ)
                                    .join())
                .hasCauseInstanceOf(ChangeConflictException.class);

        assertThat(mds.updateUserRepositoryRole(author, project1, repo1, user1, RepositoryRole.WRITE)
                      .join().major())
                .isEqualTo(revision.major() + 1);

        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, user1).join())
                .isSameAs(RepositoryRole.WRITE));

        // Updating the same operation will return the same revision.
        assertThat(mds.updateUserRepositoryRole(author, project1, repo1, user1, RepositoryRole.WRITE)
                      .join().major())
                .isEqualTo(revision.major() + 1);

        // Update invalid user
        assertThatThrownBy(() -> mds.updateUserRepositoryRole(author, project1, repo1,
                                                              user2, RepositoryRole.WRITE).join())
                .hasCauseInstanceOf(MemberNotFoundException.class);

        assertThat(mds.removeUserRepositoryRole(author, project1, repo1, user1).join().major())
                .isEqualTo(revision.major() + 2);
        assertThatThrownBy(() -> mds.removeUserRepositoryRole(author, project1, repo1, user1).join())
                .hasCauseInstanceOf(MemberNotFoundException.class);

        assertThat(mds.findRepositoryRole(project1, repo1, user1).join()).isNull();
    }

    @Test
    void tokenRepositoryRole() {
        final MetadataService mds = newMetadataService(manager);

        createRepoAndRegisterToken(mds, app1);

        // Token 'app2' is not created yet.
        assertThatThrownBy(() -> mds.addAppIdentity(author, project1, app2, ProjectRole.MEMBER).join())
                .isInstanceOf(AppIdentityNotFoundException.class);

        assertThat(mds.findRepositoryRole(project1, repo1, appToken1).join()).isNull();

        // Token 'app2' is not registered in the system, so it cannot be added to the repository.
        assertThatThrownBy(() -> mds.addAppIdentityRepositoryRole(author, project1,
                                                                  repo1, app2, RepositoryRole.READ))
                .isInstanceOf(AppIdentityNotFoundException.class);

        // Add token repository role without registering to the project first.
        mds.addAppIdentityRepositoryRole(author, project1, repo1, app1, RepositoryRole.READ).join();

        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, appToken1).join())
                .isSameAs(RepositoryRole.READ));

        // Try once more - should fail due to duplication
        assertThatThrownBy(() -> mds.addAppIdentityRepositoryRole(author, project1,
                                                                  repo1, app1, RepositoryRole.READ)
                                    .join())
                .hasCauseInstanceOf(ChangeConflictException.class);

        final Revision revision =
                mds.updateAppIdentityRepositoryRole(author, project1, repo1, app1, RepositoryRole.WRITE).join();
        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, appToken1).join())
                .isSameAs(RepositoryRole.WRITE));

        // Update invalid token
        assertThatThrownBy(() -> mds.updateAppIdentityRepositoryRole(author, project1, repo1, app2,
                                                                     RepositoryRole.WRITE).join())
                .hasCauseInstanceOf(AppIdentityNotFoundException.class);

        // Update again with the same permission.
        assertThat(mds.updateAppIdentityRepositoryRole(author, project1, repo1, app1, RepositoryRole.WRITE)
                      .join())
                .isEqualTo(revision);

        mds.removeAppIdentityRepositoryRole(author, project1, repo1, app1).join();
        assertThatThrownBy(() -> mds.removeAppIdentityRepositoryRole(author, project1, repo1, app1).join())
                .hasCauseInstanceOf(ChangeConflictException.class);

        assertThat(mds.findRepositoryRole(project1, repo1, appToken1).join()).isNull();
    }

    @Test
    void certificateRepositoryRole() {
        final MetadataService mds = newMetadataService(manager);

        createRepoAndRegisterCertificate(mds, cert1, certificateId1);

        // Certificate 'cert2' is not created yet.
        assertThatThrownBy(() -> mds.addAppIdentity(author, project1, cert2, ProjectRole.MEMBER).join())
                .isInstanceOf(AppIdentityNotFoundException.class);

        assertThat(mds.findRepositoryRole(project1, repo1, certificate1).join()).isNull();

        // Certificate 'cert2' is not registered in the system, so it cannot be added to the repository.
        assertThatThrownBy(() -> mds.addAppIdentityRepositoryRole(author, project1,
                                                                  repo1, cert2, RepositoryRole.READ))
                .isInstanceOf(AppIdentityNotFoundException.class);

        // Add certificate repository role without registering to the project first.
        mds.addAppIdentityRepositoryRole(author, project1, repo1, cert1, RepositoryRole.READ).join();

        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, certificate1).join())
                .isSameAs(RepositoryRole.READ));

        // Try once more - should fail due to duplication
        assertThatThrownBy(() -> mds.addAppIdentityRepositoryRole(author, project1, repo1, cert1,
                                                                  RepositoryRole.READ).join())
                .hasCauseInstanceOf(ChangeConflictException.class);

        final Revision revision =
                mds.updateAppIdentityRepositoryRole(author, project1, repo1, cert1, RepositoryRole.WRITE)
                   .join();

        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, certificate1).join())
                .isSameAs(RepositoryRole.WRITE));

        // updating non-existent certificate fails
        assertThatThrownBy(() -> mds.updateAppIdentityRepositoryRole(author, project1, repo1, cert2,
                                                                     RepositoryRole.WRITE).join())
                .hasCauseInstanceOf(AppIdentityNotFoundException.class);

        // updating with same role returns same revision
        assertThat(mds.updateAppIdentityRepositoryRole(author, project1, repo1, cert1, RepositoryRole.WRITE)
                      .join())
                .isEqualTo(revision);

        mds.removeAppIdentityRepositoryRole(author, project1, repo1, cert1).join();

        // duplicate removal fails
        assertThatThrownBy(() -> mds.removeAppIdentityRepositoryRole(author, project1, repo1, cert1).join())
                .hasCauseInstanceOf(ChangeConflictException.class);
        assertThat(mds.findRepositoryRole(project1, repo1, certificate1).join()).isNull();
    }

    @Test
    void removeMember() {
        final MetadataService mds = newMetadataService(manager);

        mds.addRepo(author, project1, repo1, ProjectRoles.of(null, null)).join();

        mds.addMember(author, project1, user1, ProjectRole.MEMBER).join();
        mds.addMember(author, project1, user2, ProjectRole.MEMBER).join();
        await().untilAsserted(() -> assertThat(mds.getMember(project1, user2).join()).isNotNull());
        mds.addUserRepositoryRole(author, project1, repo1, user1, RepositoryRole.READ).join();
        mds.addUserRepositoryRole(author, project1, repo1, user2, RepositoryRole.READ).join();

        assertThat(mds.getMember(project1, user1).join().id()).isNotNull();
        assertThat(mds.getMember(project1, user2).join().id()).isNotNull();

        assertThat(mds.findRepositoryRole(project1, repo1, user1).join()).isSameAs(RepositoryRole.READ);

        // Remove 'user1' from the project.
        mds.removeMember(author, project1, user1).join();
        // Remove user repository role of 'user1', too.
        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, user1).join()).isNull());

        assertThat(mds.findRepositoryRole(project1, repo1, user2).join()).isSameAs(RepositoryRole.READ);

        // Remove 'user1' again.
        assertThatThrownBy(() -> mds.removeMember(author, project1, user1).join())
                .hasCauseInstanceOf(MemberNotFoundException.class);
    }

    @Test
    void removeToken() {
        final MetadataService mds = newMetadataService(manager);

        mds.addRepo(author, project1, repo1, ProjectRoles.of(null, null)).join();
        mds.createToken(author, app1).join();
        mds.createToken(author, app2).join();

        mds.addAppIdentity(author, project1, app1, ProjectRole.MEMBER).join();
        mds.addAppIdentity(author, project1, app2, ProjectRole.MEMBER).join();
        await().until(() -> mds.findAppIdentity(app2) != null);
        mds.addAppIdentityRepositoryRole(author, project1, repo1, app1, RepositoryRole.READ).join();
        mds.addAppIdentityRepositoryRole(author, project1, repo1, app2, RepositoryRole.READ).join();

        assertThat(mds.findRepositoryRole(project1, repo1, appToken1).join()).isSameAs(RepositoryRole.READ);

        // Remove 'app1' from the project.
        mds.removeAppIdentityFromProject(author, project1, app1).join();
        // Remove token repository role of 'app1', too.
        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, appToken1).join())
                .isNull());

        assertThat(mds.findRepositoryRole(project1, repo1, appToken2).join()).isSameAs(RepositoryRole.READ);

        // Remove 'app1' again.
        assertThatThrownBy(() -> mds.removeAppIdentityFromProject(author, project1, app1).join())
                .hasCauseInstanceOf(AppIdentityNotFoundException.class);
    }

    @Test
    void destroyToken() {
        final MetadataService mds = newMetadataService(manager);

        mds.addRepo(author, project1, repo1, ProjectRoles.of(null, null)).join();
        mds.createToken(author, app1).join();
        mds.createToken(author, app2).join();

        mds.addAppIdentity(author, project1, app1, ProjectRole.MEMBER).join();
        mds.addAppIdentity(author, project1, app2, ProjectRole.MEMBER).join();

        await().until(() -> mds.findAppIdentity(app2) != null);
        mds.addAppIdentityRepositoryRole(author, project1, repo1, app1, RepositoryRole.READ).join();
        mds.addAppIdentityRepositoryRole(author, project1, repo1, app2, RepositoryRole.READ).join();

        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, appToken1).join())
                .isSameAs(RepositoryRole.READ));

        // Remove 'app1' from the system completely.
        mds.destroyToken(author, app1).join();
        mds.purgeAppIdentity(author, app1);

        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, appToken1).join())
                .isNull());

        assertThat(mds.findRepositoryRole(project1, repo1, appToken2).join()).isSameAs(RepositoryRole.READ);

        // Remove 'app1' again.
        assertThatThrownBy(() -> mds.destroyToken(author, app1).join())
                .hasCauseInstanceOf(AppIdentityNotFoundException.class);
    }

    @Test
    void destroyCertificate() {
        final MetadataService mds = newMetadataService(manager);

        mds.addRepo(author, project1, repo1, ProjectRoles.of(null, null)).join();
        mds.createCertificate(author, cert1, certificateId1, false).join();
        mds.createCertificate(author, cert2, certificateId2, false).join();

        mds.addAppIdentity(author, project1, cert1, ProjectRole.MEMBER).join();
        mds.addAppIdentity(author, project1, cert2, ProjectRole.MEMBER).join();

        await().until(() -> mds.findAppIdentity(cert2) != null);
        mds.addAppIdentityRepositoryRole(author, project1, repo1, cert1, RepositoryRole.READ).join();
        mds.addAppIdentityRepositoryRole(author, project1, repo1, cert2, RepositoryRole.READ).join();

        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, certificate1).join())
                .isSameAs(RepositoryRole.READ));

        // Remove 'cert1' from the system completely.
        mds.destroyCertificate(author, cert1).join();
        mds.purgeAppIdentity(author, cert1);

        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, certificate1).join())
                .isNull());

        assertThat(mds.findRepositoryRole(project1, repo1, certificate2).join()).isSameAs(RepositoryRole.READ);

        // Remove 'cert1' again.
        assertThatThrownBy(() -> mds.destroyCertificate(author, cert1).join())
                .hasCauseInstanceOf(AppIdentityNotFoundException.class);
    }

    @Test
    void removeCertificate() {
        final MetadataService mds = newMetadataService(manager);

        mds.addRepo(author, project1, repo1, ProjectRoles.of(null, null)).join();
        mds.createCertificate(author, cert1, certificateId1, false).join();
        mds.createCertificate(author, cert2, certificateId2, false).join();

        mds.addAppIdentity(author, project1, cert1, ProjectRole.MEMBER).join();
        mds.addAppIdentity(author, project1, cert2, ProjectRole.MEMBER).join();
        await().until(() -> mds.findAppIdentity(cert2) != null);
        mds.addAppIdentityRepositoryRole(author, project1, repo1, cert1, RepositoryRole.READ).join();
        mds.addAppIdentityRepositoryRole(author, project1, repo1, cert2, RepositoryRole.READ).join();

        assertThat(mds.findRepositoryRole(project1, repo1, certificate1).join()).isSameAs(RepositoryRole.READ);

        // Remove 'cert1' from the project.
        mds.removeAppIdentityFromProject(author, project1, cert1).join();
        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, certificate1).join())
                .isNull());

        assertThat(mds.findRepositoryRole(project1, repo1, certificate2).join()).isSameAs(RepositoryRole.READ);

        // Remove 'cert1' again.
        assertThatThrownBy(() -> mds.removeAppIdentityFromProject(author, project1, cert1).join())
                .hasCauseInstanceOf(AppIdentityNotFoundException.class);
    }

    @Test
    void tokenActivationAndDeactivation() {
        final MetadataService mds = newMetadataService(manager);

        mds.createToken(author, app1).join();
        await().untilAsserted(() -> assertThat(mds.getAppIdentityRegistry().get(app1)).isNotNull());
        assertThat(mds.getAppIdentityRegistry().get(app1).creation().user()).isEqualTo(owner.id());

        final Revision revision = mds.deactivateToken(author, app1).join();
        await().untilAsserted(() -> assertThat(mds.getAppIdentityRegistry().get(app1).isActive()).isFalse());
        final AppIdentity appIdentity = mds.getAppIdentityRegistry().get(app1);
        assertThat(appIdentity.deactivation()).isNotNull();
        assertThat(appIdentity.deactivation().user()).isEqualTo(owner.id());

        // Executing the same operation will return the same revision.
        assertThat(mds.deactivateToken(author, app1).join()).isEqualTo(revision);

        assertThat(mds.activateToken(author, app1).join().major()).isEqualTo(revision.major() + 1);
        await().untilAsserted(() -> assertThat(mds.getAppIdentityRegistry().get(app1).isActive()).isTrue());

        // Executing the same operation will return the same revision.
        assertThat(mds.activateToken(author, app1).join().major()).isEqualTo(revision.major() + 1);
    }

    @Test
    void certificateActivationAndDeactivation() {
        final MetadataService mds = newMetadataService(manager);

        mds.createCertificate(author, cert1, certificateId1, false).join();
        await().untilAsserted(() -> assertThat(mds.getAppIdentityRegistry().get(cert1)).isNotNull());
        assertThat(mds.getAppIdentityRegistry().get(cert1).creation().user()).isEqualTo(owner.id());
        assertThat(mds.getAppIdentityRegistry().get(cert1).type()).isEqualTo(AppIdentityType.CERTIFICATE);

        final Revision revision = mds.deactivateCertificate(author, cert1).join();
        await().untilAsserted(() -> assertThat(mds.getAppIdentityRegistry().get(cert1).isActive()).isFalse());
        final AppIdentity appIdentity = mds.getAppIdentityRegistry().get(cert1);
        assertThat(appIdentity.deactivation()).isNotNull();
        assertThat(appIdentity.deactivation().user()).isEqualTo(owner.id());

        // Executing the same operation will return the same revision.
        assertThat(mds.deactivateCertificate(author, cert1).join()).isEqualTo(revision);

        assertThat(mds.activateCertificate(author, cert1).join().major()).isEqualTo(revision.major() + 1);
        await().untilAsserted(() -> assertThat(mds.getAppIdentityRegistry().get(cert1).isActive()).isTrue());

        // Executing the same operation will return the same revision.
        assertThat(mds.activateCertificate(author, cert1).join().major()).isEqualTo(revision.major() + 1);
    }

    @Test
    void updateUser() {
        final MetadataService mds = newMetadataService(manager);

        mds.createToken(author, app1).join();
        await().untilAsserted(() -> assertThat(mds.getAppIdentityRegistry().get(app1)).isNotNull());
        assertThat(mds.getAppIdentityRegistry().get(app1).isSystemAdmin()).isFalse();

        final AppIdentityRegistry registry = mds.getAppIdentityRegistry();
        // registry is cached so the same instance is returned.
        assertThat(registry).isSameAs(mds.getAppIdentityRegistry());

        final Revision revision = mds.updateAppIdentityLevel(author, app1, true).join();
        await().untilAsserted(() -> assertThat(mds.getAppIdentityRegistry().get(app1)
                                                  .isSystemAdmin()).isTrue());
        // Now the reference is different.
        assertThat(mds.getAppIdentityRegistry()).isNotSameAs(registry);

        assertThat(mds.updateAppIdentityLevel(author, app1, true).join()).isEqualTo(revision);

        assertThat(mds.updateAppIdentityLevel(author, app1, false).join()).isEqualTo(revision.forward(1));
        await().untilAsserted(() -> assertThat(mds.getAppIdentityRegistry().get(app1)
                                                  .isSystemAdmin()).isFalse());
        assertThat(mds.updateAppIdentityLevel(author, app1, false).join()).isEqualTo(revision.forward(1));
    }

    @Test
    void effectiveRepositoryRole_userWithProjectMembership() {
        // When a user is both a project member and has an explicit repository role,
        // the effective role should be the max of the two.
        final MetadataService mds = newMetadataService(manager);

        // Create a repo with member default = WRITE.
        mds.addRepo(author, project1, repo1,
                     ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.READ)).join();
        await().until(() -> getRepo1(mds) != null);

        // Register user1 as a project member.
        mds.addMember(author, project1, user1, ProjectRole.MEMBER).join();

        // Assign an explicit READ repository role.
        mds.addUserRepositoryRole(author, project1, repo1, user1, RepositoryRole.READ).join();

        // Effective role should be WRITE (max of explicit READ and member default WRITE).
        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, user1).join())
                .isSameAs(RepositoryRole.WRITE));
    }

    @Test
    void effectiveRepositoryRole_userWithoutProjectMembership() {
        // When a user is NOT a project member but has an explicit repository role,
        // the effective role should be the max of the explicit role and the guest default.
        final MetadataService mds = newMetadataService(manager);

        // Create a repo with member default = WRITE, guest default = READ.
        mds.addRepo(author, project1, repo1,
                     ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.READ)).join();
        await().until(() -> getRepo1(mds) != null);

        // user1 is NOT a project member. Assign an explicit WRITE repository role.
        mds.addUserRepositoryRole(author, project1, repo1, user1, RepositoryRole.WRITE).join();

        // Effective role should be WRITE (max of explicit WRITE and guest default READ).
        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, user1).join())
                .isSameAs(RepositoryRole.WRITE));

        // user2 has no explicit role and is not a project member. Should get the guest default READ.
        assertThat(mds.findRepositoryRole(project1, repo1, user2).join())
                .isSameAs(RepositoryRole.READ);
    }

    @Test
    void effectiveRepositoryRole_appIdentityWithProjectRegistration() {
        // When an app identity is registered at the project level and has an explicit repository role,
        // the effective role should be the max of the two.
        final MetadataService mds = newMetadataService(manager);

        // Create a repo with member default = WRITE.
        mds.addRepo(author, project1, repo1,
                     ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.READ)).join();
        mds.createToken(author, app1).join();
        await().untilAsserted(() -> assertThat(mds.findAppIdentity(app1)).isNotNull());

        // Register to the project as MEMBER.
        mds.addAppIdentity(author, project1, app1, ProjectRole.MEMBER).join();

        // Assign an explicit READ repository role.
        mds.addAppIdentityRepositoryRole(author, project1, repo1, app1, RepositoryRole.READ).join();

        // Effective role should be WRITE (max of explicit READ and member default WRITE).
        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, appToken1).join())
                .isSameAs(RepositoryRole.WRITE));
    }

    @Test
    void effectiveRepositoryRole_appIdentityWithoutProjectRegistration() {
        // When an app identity is NOT registered at the project level but has an explicit repository role,
        // the effective role should be the max of the explicit role and the guest default.
        final MetadataService mds = newMetadataService(manager);

        // Create a repo with member default = WRITE, guest default = READ.
        mds.addRepo(author, project1, repo1,
                     ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.READ)).join();
        mds.createToken(author, app1).join();
        await().untilAsserted(() -> assertThat(mds.findAppIdentity(app1)).isNotNull());

        // Do NOT register to the project. Assign an explicit WRITE repository role.
        mds.addAppIdentityRepositoryRole(author, project1, repo1, app1, RepositoryRole.WRITE).join();

        // Effective role should be WRITE (max of explicit WRITE and guest default READ).
        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, appToken1).join())
                .isSameAs(RepositoryRole.WRITE));
    }

    @Test
    void effectiveRepositoryRole_appIdentityWithoutGuestAccess() {
        // When an app identity has allowGuestAccess=false, is NOT registered at the project level,
        // but has an explicit repository role, it should still be accessible.
        final MetadataService mds = newMetadataService(manager);

        final Token noGuestToken = new Token(app1, "secret", false, false, UserAndTimestamp.of(author));

        mds.addRepo(author, project1, repo1,
                     ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.READ)).join();
        mds.createToken(author, app1).join();
        await().untilAsserted(() -> assertThat(mds.findAppIdentity(app1)).isNotNull());

        // Assign an explicit READ repository role without project registration.
        mds.addAppIdentityRepositoryRole(author, project1, repo1, app1, RepositoryRole.READ).join();

        // Should be accessible with the explicit repository role despite allowGuestAccess=false.
        await().untilAsserted(() -> assertThat(mds.findRepositoryRole(project1, repo1, noGuestToken).join())
                .isSameAs(RepositoryRole.READ));

        // Without an explicit repository role, allowGuestAccess=false should deny access.
        assertThat(mds.findRepositoryRole(project1, repo1,
                new Token(app2, "secret2", false, false, UserAndTimestamp.of(author))).join())
                .isNull();
    }

    private static RepositoryMetadata getRepo1(MetadataService mds) {
        final ProjectMetadata metadata = mds.getProject(project1).join();
        return metadata.repo(repo1);
    }

    private static MetadataService newMetadataService(ProjectManagerExtension extension) {
        return new MetadataService(extension.projectManager(), extension.executor(),
                                   extension.internalProjectInitializer());
    }

    private static ProjectMetadata getProject(MetadataService mds, String projectName) {
        return mds.getProject(projectName).join();
    }

    private static void createRepoAndRegisterToken(MetadataService mds, String appId) {
        createRepo(mds);
        createTokenAndVerifyDuplicateFails(mds, appId);
        waitUntilAppIdentityRegistered(mds, appId);
    }

    private static void createRepoAndRegisterCertificate(MetadataService mds,
                                                         String appId, String certificateId) {
        createRepo(mds);
        createCertificateAndVerifyDuplicateFails(mds, appId, certificateId);
        waitUntilAppIdentityRegistered(mds, appId);
    }

    private static void createRepo(MetadataService mds) {
        mds.addRepo(author, project1, repo1, ProjectRoles.of(null, null)).join();
    }

    private static void createTokenAndVerifyDuplicateFails(MetadataService mds, String appId) {
        mds.createToken(author, appId).join();
        assertThatThrownBy(() -> mds.createToken(author, appId).join())
                .hasCauseInstanceOf(ChangeConflictException.class);
    }

    private static void createCertificateAndVerifyDuplicateFails(MetadataService mds, String appId,
                                                                 String certificateId) {
        mds.createCertificate(author, appId, certificateId, false).join();
        assertThatThrownBy(() -> mds.createCertificate(author, appId, certificateId, false).join())
                .hasCauseInstanceOf(ChangeConflictException.class);
    }

    private static void waitUntilAppIdentityRegistered(MetadataService mds, String appId) {
        await().untilAsserted(() -> assertThat(mds.findAppIdentity(appId)).isNotNull());
    }
}
