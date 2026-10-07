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
package com.linecorp.centraldogma.it.mirror.git;

import static com.linecorp.centraldogma.internal.CredentialUtil.credentialFile;
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.EncryptionConfig;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.internal.TemporaryFolderExtension;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class PreserveRemoteCommitHistoryEncryptedTest {

    private static final String PROJECT_NAME = "encrypted-history";
    private static final String REPOSITORY_NAME = "config";
    private static final String TAG_PREFIX = "refs/tags/dogma-";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.encryption(new EncryptionConfig(true, false, "test-kek"))
                   .pluginConfigs(new MirroringServicePluginConfig(true, 1, 32, 1048576L, false));
        }
    };

    @RegisterExtension
    final TemporaryFolderExtension gitRepoDir = new TemporaryFolderExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    private static CentralDogma client;
    private static MirroringService mirroringService;

    private Git git;
    private File gitWorkTree;

    @BeforeAll
    static void init() {
        client = dogma.client();
        mirroringService = dogma.mirroringService();
    }

    @BeforeEach
    void setUp() throws Exception {
        gitWorkTree = gitRepoDir.getRoot().resolve("upstream").toFile().getAbsoluteFile();
        final Repository gitRepository = new FileRepositoryBuilder().setWorkTree(gitWorkTree).build();
        gitRepository.create();
        git = Git.wrap(gitRepository);
        git.commit().setMessage("Initial commit").call();

        client.createProject(PROJECT_NAME).join();
        client.createRepository(PROJECT_NAME, REPOSITORY_NAME).join();
        assertThat(dogma.projectManager().get(PROJECT_NAME).repos().get(REPOSITORY_NAME).isEncrypted())
                .isTrue();
        pushMirrorSettings();
    }

    @AfterEach
    void tearDown() {
        git.close();
        client.removeProject(PROJECT_NAME).join();
    }

    @Test
    void preservesHistoryWithoutPublishingTags() throws Exception {
        mirroringService.mirror().join();
        final Revision baseline = headRevision();
        final RevCommit first = commitFile(
                "encrypted.txt", "secret-1", "Update encrypted repository once");
        final RevCommit second = commitFile(
                "encrypted.txt", "secret-2", "Update encrypted repository twice");

        mirroringService.mirror().join();

        assertThat(headRevision()).isEqualTo(baseline.forward(2));
        assertThat(fileContent(baseline.forward(1))).isEqualTo("secret-1");
        assertThat(fileContent(baseline.forward(2))).isEqualTo("secret-2");
        assertThat(client.getHistory(PROJECT_NAME, REPOSITORY_NAME, Revision.HEAD,
                                     baseline.forward(1), PathPattern.all(), 0).join())
                .extracting(Commit::upstreamCommitId)
                .containsOnlyNulls();
        final Repository repository =
                dogma.projectManager().get(PROJECT_NAME).repos().get(REPOSITORY_NAME).jGitRepository();
        assertThat(repository.exactRef(TAG_PREFIX + first.name())).isNull();
        assertThat(repository.exactRef(TAG_PREFIX + second.name())).isNull();
    }

    private RevCommit commitFile(String path, String content, String message) throws Exception {
        final File file = new File(gitWorkTree, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        git.add().addFilepattern(path).call();
        return git.commit().setMessage(message).call();
    }

    private Revision headRevision() {
        return client.normalizeRevision(PROJECT_NAME, REPOSITORY_NAME, Revision.HEAD).join();
    }

    private String fileContent(Revision revision) {
        return client.forRepo(PROJECT_NAME, REPOSITORY_NAME)
                     .file("/encrypted.txt")
                     .get(revision)
                     .join()
                     .contentAsText()
                     .trim();
    }

    private void pushMirrorSettings() {
        final String credentialName = credentialName(PROJECT_NAME, "none");
        client.forRepo(PROJECT_NAME, Project.REPO_DOGMA)
              .commit("Add /credentials/none",
                      Change.ofJsonUpsert(credentialFile(credentialName),
                                          "{ \"type\": \"NONE\", \"name\": \"" + credentialName +
                                          "\", \"enabled\": true }"))
              .push().join();
        final String gitUri = "git+file://" +
                              (gitWorkTree.getPath().startsWith(File.separator) ? "" : "/") +
                              gitWorkTree.getPath().replace(File.separatorChar, '/') + "/.git";
        client.forRepo(PROJECT_NAME, Project.REPO_DOGMA)
              .commit("Add a mirror",
                      Change.ofJsonUpsert(
                              "/repos/" + REPOSITORY_NAME + "/mirrors/history.json",
                              '{' +
                              "  \"id\": \"history\"," +
                              "  \"enabled\": true," +
                              "  \"direction\": \"REMOTE_TO_LOCAL\"," +
                              "  \"localRepo\": \"" + REPOSITORY_NAME + "\"," +
                              "  \"localPath\": \"/\"," +
                              "  \"remoteUri\": \"" + gitUri + "\"," +
                              "  \"schedule\": \"0 0 0 1 1 ? 2099\"," +
                              "  \"credentialName\": \"" + credentialName + "\"," +
                              "  \"preserveRemoteCommitHistory\": true," +
                              "  \"publishRemoteCommitTags\": false" +
                              '}'))
              .push().join();
    }
}
