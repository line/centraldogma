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
package com.linecorp.centraldogma.server.internal.storage;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static com.linecorp.centraldogma.server.internal.storage.repository.MirrorConfig.DEFAULT_SCHEDULE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.MirrorRequest;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.internal.credential.PasswordCredential;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class MigratingMetaToDogmaRepositoryServiceTest {

    private static final String TEST_PROJ = "fooProj";
    private static final String TEST_REPO = "repo";

    @RegisterExtension
    static ProjectManagerExtension projectManagerExtension = new ProjectManagerExtension() {

        @Override
        protected void afterExecutorStarted() {
            final ProjectManager projectManager = projectManagerExtension.projectManager();
            final Project project = projectManager.create(TEST_PROJ, Author.SYSTEM);
            final RepositoryManager repoManager = project.repos();
            repoManager.create(TEST_REPO, Author.SYSTEM);

            final MetadataService mds =
                    new MetadataService(projectManager, projectManagerExtension.executor(),
                                        projectManagerExtension.internalProjectInitializer());
            mds.addRepo(Author.SYSTEM, TEST_PROJ, TEST_REPO).join();
        }
    };

    @Timeout(20)
    @Test
    void migrate() throws Exception {
        final ProjectManager projectManager = projectManagerExtension.projectManager();
        final Project project = projectManager.get(TEST_PROJ);
        final MetaRepository metaRepository = project.metaRepo();
        assertThat(metaRepository.name()).isEqualTo(Project.REPO_META);
        final PasswordCredential projectCredential = new PasswordCredential(credentialName(TEST_PROJ, "alice"),
                                                                            "user", "password");
        Command<CommitResult> command = metaRepository.createCredentialPushCommand(projectCredential,
                                                                                   Author.SYSTEM, false)
                                                      .join();
        final CommandExecutor commandExecutor = projectManagerExtension.executor();
        CommitResult commitResult = commandExecutor.execute(command).join();
        assertThat(commitResult.revision().major()).isEqualTo(2);

        final String repoCredentialName = credentialName(TEST_PROJ, TEST_REPO, "alice");
        final PasswordCredential repoCredential =
                new PasswordCredential(repoCredentialName, "user", "password");
        command = metaRepository.createCredentialPushCommand(TEST_REPO, repoCredential, Author.SYSTEM, false)
                                .join();
        commitResult = commandExecutor.execute(command).join();
        assertThat(commitResult.revision().major()).isEqualTo(3);

        final MirrorRequest mirrorRequest =
                new MirrorRequest("foo", true, TEST_PROJ, DEFAULT_SCHEDULE,
                                  "LOCAL_TO_REMOTE", TEST_REPO,
                                  "/local/path", "git+ssh", "foo.com/foo.git", "",
                                  "", null, repoCredentialName, null);
        command = metaRepository.createMirrorPushCommand(
                TEST_REPO, mirrorRequest, Author.SYSTEM, null, false).join();
        commitResult = commandExecutor.execute(command).join();
        assertThat(commitResult.revision().major()).isEqualTo(4);

        // Add a dummy file that will be not copied to the dogma repository.
        commitResult = project.repos().get(Project.REPO_META).commit(
                Revision.HEAD, System.currentTimeMillis(),
                Author.SYSTEM, "dummy file", Change.ofJsonUpsert("/dummy.json", "{\"a\": \"b\"}")).join();
        // Meta repository revision;
        assertThat(commitResult.revision().major()).isEqualTo(5);

        // Dogma repository revision is 3.
        Revision revision = project.repos().get(Project.REPO_DOGMA).normalizeNow(Revision.HEAD);
        assertThat(revision.major()).isEqualTo(3);

        final MigratingMetaToDogmaRepositoryService migrationService =
                new MigratingMetaToDogmaRepositoryService(
                        projectManager, projectManagerExtension.executor(),
                        projectManagerExtension.internalProjectInitializer());
        migrationService.migrate();

        // Dogma repository revision increased by 3.
        // read-only, commit, active
        revision = project.repos().get(Project.REPO_DOGMA).normalizeNow(Revision.HEAD);
        assertThat(revision.major()).isEqualTo(6);

        assertThat(project.metaRepo().name()).isEqualTo(Project.REPO_DOGMA);
        final Repository repository = project.repos().get(Project.REPO_DOGMA);
        final Map<String, Entry<?>> map = repository.find(Revision.HEAD, "/**").join();
        final List<String> files = map.entrySet().stream()
                                      .filter(e -> e.getValue().type() != EntryType.DIRECTORY)
                                      .map(Map.Entry::getKey).collect(toImmutableList());
        assertThat(files.size()).isEqualTo(5);
        assertThat(files).containsExactlyInAnyOrder(
                "/credentials/alice.json",
                "/meta-to-dogma-migrated.json",
                "/metadata.json",
                "/repos/repo/credentials/alice.json",
                "/repos/repo/mirrors/foo.json");

        // Check if the dogma repository is active.
        commitResult = project.repos().get(Project.REPO_DOGMA).commit(
                Revision.HEAD, System.currentTimeMillis(),
                Author.SYSTEM, "dummy file", Change.ofJsonUpsert("/foo.json", "{\"a\": \"b\"}")).join();
        assertThat(commitResult.revision().major()).isEqualTo(7);
    }
}
