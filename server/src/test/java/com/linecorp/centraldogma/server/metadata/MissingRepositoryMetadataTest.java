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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.storage.encryption.NoopEncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

/**
 * Makes sure {@link MetadataService} adds the default metadata of a repository when the {@code metadata.json}
 * does not contain the repository metadata.
 */
class MissingRepositoryMetadataTest {

    @RegisterExtension
    final ProjectManagerExtension manager = new ProjectManagerExtension() {
        @Override
        protected void afterExecutorStarted() {
            // Create a project and its metadata here.
            executor().execute(Command.createProject(AUTHOR, PROJ)).join();
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    private static final Author AUTHOR = Author.SYSTEM;
    private static final String PROJ = "proj";

    @Test
    void missingRepositoryMetadata() {
        final ProjectManager pm = manager.projectManager();
        final RepositoryManager rm = pm.get(PROJ).repos();
        final CommandExecutor executor = manager.executor();
        final InternalProjectInitializer projectInitializer =
                new InternalProjectInitializer(executor, pm, NoopEncryptionStorageManager.INSTANCE);

        // Create a new repository without adding metadata.
        rm.create("repo", AUTHOR);
        assertThat(rm.get("repo")).isNotNull();

        // Try to access the repository metadata, which will trigger its auto-generation.
        final MetadataService mds = new MetadataService(pm, executor, projectInitializer);
        final RepositoryMetadata metadata = mds.getRepo(PROJ, "repo").join();
        assertThat(metadata.id()).isEqualTo("repo");
        assertThat(metadata.name()).isEqualTo("repo");
        final Roles roles = metadata.roles();
        assertThat(roles.projectRoles().member()).isSameAs(RepositoryRole.WRITE);
        assertThat(roles.projectRoles().guest()).isNull();
        assertThat(roles.users()).isEmpty();
        assertThat(roles.applications()).isEmpty();

        // However, the metadata of a non-existent repository must not trigger auto-generation.
        assertThatThrownBy(() -> mds.getRepo(PROJ, "missing").join())
                .hasCauseInstanceOf(RepositoryNotFoundException.class)
                .hasMessageContaining("missing");
    }
}
