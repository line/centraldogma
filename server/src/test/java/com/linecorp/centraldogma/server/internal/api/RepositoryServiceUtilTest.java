/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static com.linecorp.centraldogma.server.metadata.RepositoryMetadata.DEFAULT_PROJECT_ROLES;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.ProjectRoles;
import com.linecorp.centraldogma.server.metadata.RepositoryMetadata;
import com.linecorp.centraldogma.server.storage.encryption.NoopEncryptionStorageManager;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class RepositoryServiceUtilTest {

    private static final String project = "foo";

    private static final Author userAuthor = Author.ofEmail("user@localhost.localdomain");
    private static final Author appAuthor =
            new Author("app-1", "app-1" + Util.APP_IDENTITY_EMAIL_SUFFIX);

    @RegisterExtension
    final ProjectManagerExtension manager = new ProjectManagerExtension() {
        @Override
        protected void afterExecutorStarted() {
            executor().execute(Command.createProject(userAuthor, project)).join();
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    private MetadataService newMetadataService() {
        return new MetadataService(manager.projectManager(), manager.executor(),
                                   manager.internalProjectInitializer());
    }

    @Test
    void defaultOverloadUsesDefaultProjectRoles() {
        final MetadataService mds = newMetadataService();
        RepositoryServiceUtil.createRepository(manager.executor(), mds, userAuthor, project, "repo1",
                                               false, null).join();

        final RepositoryMetadata metadata = mds.getRepo(project, "repo1").join();
        assertThat(metadata.roles().projectRoles()).isEqualTo(DEFAULT_PROJECT_ROLES);
        // WRITE for members, no role for guests.
        assertThat(metadata.roles().projectRoles().member()).isEqualTo(RepositoryRole.WRITE);
        assertThat(metadata.roles().projectRoles().guest()).isNull();
    }

    @Test
    void customProjectRolesArePreserved() {
        final MetadataService mds = newMetadataService();
        final ProjectRoles projectRoles = ProjectRoles.of(RepositoryRole.READ, null);
        RepositoryServiceUtil.createRepository(manager.executor(), mds, userAuthor, project, "repo2",
                                               projectRoles, false, false, null).join();

        final RepositoryMetadata metadata = mds.getRepo(project, "repo2").join();
        assertThat(metadata.roles().projectRoles()).isEqualTo(projectRoles);
        assertThat(metadata.roles().projectRoles().member()).isEqualTo(RepositoryRole.READ);
        assertThat(metadata.roles().projectRoles().guest()).isNull();
    }

    @Test
    void customProjectRolesWithGuestRole() {
        final MetadataService mds = newMetadataService();
        final ProjectRoles projectRoles = ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.READ);
        RepositoryServiceUtil.createRepository(manager.executor(), mds, userAuthor, project, "repo3",
                                               projectRoles, false, false, null).join();

        final RepositoryMetadata metadata = mds.getRepo(project, "repo3").join();
        assertThat(metadata.roles().projectRoles().member()).isEqualTo(RepositoryRole.WRITE);
        assertThat(metadata.roles().projectRoles().guest()).isEqualTo(RepositoryRole.READ);
    }

    @Test
    void notAssigningRoleToAuthorLeavesUserAndAppRolesEmpty() {
        final MetadataService mds = newMetadataService();
        RepositoryServiceUtil.createRepository(manager.executor(), mds, userAuthor, project, "repo4",
                                               ProjectRoles.of(RepositoryRole.READ, null), false, false, null)
                             .join();

        final RepositoryMetadata metadata = mds.getRepo(project, "repo4").join();
        assertThat(metadata.roles().users()).isEmpty();
        assertThat(metadata.roles().appIds()).isEmpty();
    }

    @Test
    void assigningRoleToUserAuthorGrantsAdminToUser() {
        final MetadataService mds = newMetadataService();
        RepositoryServiceUtil.createRepository(manager.executor(), mds, userAuthor, project, "repo5",
                                               ProjectRoles.of(RepositoryRole.READ, null), true, false, null)
                             .join();

        final RepositoryMetadata metadata = mds.getRepo(project, "repo5").join();
        assertThat(metadata.roles().users())
                .isEqualTo(ImmutableMap.of(userAuthor.email(), RepositoryRole.ADMIN));
        assertThat(metadata.roles().appIds()).isEmpty();
    }

    @Test
    void assigningRoleToAppAuthorGrantsAdminToApp() {
        final MetadataService mds = newMetadataService();
        RepositoryServiceUtil.createRepository(manager.executor(), mds, appAuthor, project, "repo6",
                                               ProjectRoles.of(RepositoryRole.READ, null), true, false, null)
                             .join();

        final RepositoryMetadata metadata = mds.getRepo(project, "repo6").join();
        assertThat(metadata.roles().appIds())
                .isEqualTo(ImmutableMap.of(appAuthor.name(), RepositoryRole.ADMIN));
        assertThat(metadata.roles().users()).isEmpty();
    }

    @Test
    void createEncryptedFallsBackToNoopWhenDisabled() {
        final MetadataService mds = newMetadataService();
        RepositoryServiceUtil.createRepository(manager.executor(), mds, userAuthor, project, "repo7",
                                               ProjectRoles.of(RepositoryRole.READ, null), false, false,
                                               NoopEncryptionStorageManager.INSTANCE).join();
        assertThat(manager.projectManager().get(project).repos().exists("repo7")).isTrue();
    }
}
