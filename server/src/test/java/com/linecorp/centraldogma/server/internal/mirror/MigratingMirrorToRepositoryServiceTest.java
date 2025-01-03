/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.mirror;

import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.LEGACY_MIRRORS_PATH;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class MigratingMirrorToRepositoryServiceTest {

    // The static fields are shared with MirroringMigrationServiceClusterTest.
    static final String TEST_PROJ = "fooProj";
    static final String TEST_REPO0 = "repo0";
    static final String TEST_REPO1 = "repo1";

    static final String REPO0_MIRROR_0 =
            '{' +
            "  \"id\": \"mirror-0\"," +
            "  \"type\": \"single\"," +
            "  \"enabled\": true," +
            "  \"schedule\": \"0 * * * * ?\"," +
            "  \"direction\": \"REMOTE_TO_LOCAL\"," +
            "  \"localRepo\": \"" + TEST_REPO0 + "\"," +
            "  \"localPath\": \"/\"," +
            "  \"remoteUri\": \"git+ssh://git.foo.com/foo.git/settings#release\"," +
            "  \"credentialId\": \"credential-1\"" +
            '}';

    static final String REPO0_MIRROR_1 =
            '{' +
            "  \"id\": \"mirror-1\"," +
            "  \"type\": \"single\"," +
            "  \"enabled\": true," +
            "  \"schedule\": \"0 * * * * ?\"," +
            "  \"direction\": \"REMOTE_TO_LOCAL\"," +
            "  \"localRepo\": \"" + TEST_REPO0 + "\"," +
            "  \"localPath\": \"/\"," +
            "  \"remoteUri\": \"git+ssh://git.bar.com/foo.git/settings#release\"," +
            "  \"credentialId\": \"credential-1\"" +
            '}';

    static final String REPO1_MIRROR =
            '{' +
            "  \"id\": \"mirror-2\"," +
            "  \"type\": \"single\"," +
            "  \"enabled\": true," +
            "  \"schedule\": \"0 * * * * ?\"," +
            "  \"direction\": \"REMOTE_TO_LOCAL\"," +
            "  \"localRepo\": \"" + TEST_REPO1 + "\"," +
            "  \"localPath\": \"/\"," +
            "  \"remoteUri\": \"git+ssh://git.qux.com/foo.git/settings#release\"," +
            "  \"credentialId\": \"credential-1\"" +
            '}';

    @RegisterExtension
    static ProjectManagerExtension projectManagerExtension = new ProjectManagerExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        protected void afterExecutorStarted() {
            final ProjectManager projectManager = projectManagerExtension.projectManager();
            final Project project = projectManager.create(TEST_PROJ, Author.SYSTEM);
            final RepositoryManager repoManager = project.repos();
            repoManager.create(TEST_REPO0, Author.SYSTEM);
            repoManager.create(TEST_REPO1, Author.SYSTEM);

            final MetadataService mds = new MetadataService(projectManager, projectManagerExtension.executor());
            mds.addRepo(Author.SYSTEM, TEST_PROJ, TEST_REPO0).join();
            mds.addRepo(Author.SYSTEM, TEST_PROJ, TEST_REPO1).join();
        }
    };

    @Test
    void migrate() throws Exception {
        final ProjectManager projectManager = projectManagerExtension.projectManager();
        final Project project = projectManager.get(TEST_PROJ);

        final List<Change<?>> changes = new ArrayList<>();
        changes.add(Change.ofJsonUpsert(LEGACY_MIRRORS_PATH + "mirror-0.json", REPO0_MIRROR_0));
        changes.add(Change.ofJsonUpsert(LEGACY_MIRRORS_PATH + "mirror-1.json", REPO0_MIRROR_1));
        changes.add(Change.ofJsonUpsert(LEGACY_MIRRORS_PATH + "mirror-2.json", REPO1_MIRROR));

        project.metaRepo().commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                                  "Create a legacy mirrors.json", changes).join();
        final MigratingMirrorToRepositoryService migrationService = new MigratingMirrorToRepositoryService(
                projectManager, projectManagerExtension.executor());
        migrationService.migrate();

        final Map<String, Entry<?>> entries = project.metaRepo()
                                                     .find(Revision.HEAD, "/repos/*/mirrors/*.json")
                                                     .join();
        assertThat(entries).hasSize(3);
        assertThat(entries).containsExactlyInAnyOrderEntriesOf(ImmutableMap.of(
                "/repos/" + TEST_REPO0 + "/mirrors/mirror-0.json",
                Entry.ofJson(new Revision(3),
                             "/repos/" + TEST_REPO0 + "/mirrors/mirror-0.json", REPO0_MIRROR_0),
                "/repos/" + TEST_REPO0 + "/mirrors/mirror-1.json",
                Entry.ofJson(new Revision(3),
                             "/repos/" + TEST_REPO0 + "/mirrors/mirror-1.json", REPO0_MIRROR_1),
                "/repos/" + TEST_REPO1 + "/mirrors/mirror-2.json",
                Entry.ofJson(new Revision(3),
                             "/repos/" + TEST_REPO1 + "/mirrors/mirror-2.json", REPO1_MIRROR)
        ));
    }
}
