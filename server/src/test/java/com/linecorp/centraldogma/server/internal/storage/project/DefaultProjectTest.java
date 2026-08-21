/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.storage.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.ReplayCommit;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class DefaultProjectTest {

    @SuppressWarnings("JUnitMalformedDeclaration")
    @RegisterExtension
    final ProjectManagerExtension extension = new ProjectManagerExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Test
    void metaRepositoryNotCreated() {
        final ProjectManager projectManager = extension.projectManager();
        final Project foo = projectManager.create("foo", Author.SYSTEM);
        // Meta repository is not created when a project is created.
        assertThat(foo.repos().list().keySet()).containsExactlyInAnyOrder("dogma");
        assertThat(foo.metaRepo().name()).isEqualTo("dogma");

        // No exception is raised while reopening the project.
        extension.recreateProjectManager();
    }

    /**
     * A recovery rewrites {@code <project>/dogma} in place, so the metadata arrives again at a revision the
     * project has already seen. Dropping it as stale would leave the project serving the roles and
     * permissions from the diverged history for as long as the server runs.
     */
    @Test
    void metadataFollowsARecoveredDogmaRepository() {
        final ProjectManager projectManager = extension.projectManager();
        final Project foo = projectManager.create("foo", Author.SYSTEM);
        final MetadataService mds = new MetadataService(projectManager, extension.executor(),
                                                        extension.internalProjectInitializer());

        mds.addRepo(Author.SYSTEM, "foo", "kept").join();
        final Revision keptRevision = foo.repos().get(Project.REPO_DOGMA).normalizeNow(Revision.HEAD);
        await().untilAsserted(() -> assertThat(foo.metadata().repos()).containsKey("kept"));

        mds.addRepo(Author.SYSTEM, "foo", "discarded").join();
        await().untilAsserted(() -> assertThat(foo.metadata().repos()).containsKey("discarded"));

        // Recover the metadata repository back to the revision that only knows "kept".
        final List<ReplayCommit> payload =
                foo.repos().buildRecoveryPayload(Project.REPO_DOGMA, keptRevision, keptRevision);
        assertThat(foo.repos().recoverRepository(Project.REPO_DOGMA, keptRevision.backward(1), payload))
                .isTrue();

        await().untilAsserted(() -> {
            assertThat(foo.metadata().repos()).containsKey("kept");
            assertThat(foo.metadata().repos()).doesNotContainKey("discarded");
        });
    }
}
