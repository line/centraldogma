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
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
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
import com.linecorp.centraldogma.testing.internal.TestUtil;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class PreserveRemoteCommitHistoryEncryptedTest {

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
    private String projectName;

    @BeforeAll
    static void init() {
        client = dogma.client();
        mirroringService = dogma.mirroringService();
    }

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        projectName = TestUtil.normalizedDisplayName(testInfo);
        gitWorkTree = gitRepoDir.getRoot().resolve("upstream").toFile().getAbsoluteFile();
        final Repository gitRepository = new FileRepositoryBuilder().setWorkTree(gitWorkTree).build();
        gitRepository.create();
        git = Git.wrap(gitRepository);
        git.commit().setMessage("Initial commit").call();

        client.createProject(projectName).join();
        client.createRepository(projectName, REPOSITORY_NAME).join();
        assertThat(dogma.projectManager().get(projectName).repos().get(REPOSITORY_NAME).isEncrypted())
                .isTrue();
    }

    @AfterEach
    void tearDown() {
        git.close();
        client.removeProject(projectName).join();
    }

    @Test
    void preservesHistoryAndPublishesTags() throws Exception {
        pushMirrorSettings(true);
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
        assertThat(client.getHistory(projectName, REPOSITORY_NAME, Revision.HEAD,
                                     baseline.forward(1), PathPattern.all(), 0).join())
                .extracting(Commit::upstreamCommitId)
                .containsExactly(second.name(), first.name());
        final Repository repository =
                dogma.projectManager().get(projectName).repos().get(REPOSITORY_NAME).jGitRepository();
        final ObjectId firstTag = repository.exactRef(TAG_PREFIX + first.name()).getObjectId();
        final ObjectId secondTag = repository.exactRef(TAG_PREFIX + second.name()).getObjectId();
        assertThat(firstTag).isNotEqualTo(secondTag);
        assertThat(secondTag).isEqualTo(
                repository.exactRef(Constants.R_HEADS + Constants.MASTER).getObjectId());
        assertThat(repository.getRefDatabase().getRefsByPrefix(Constants.R_TAGS))
                .extracting(org.eclipse.jgit.lib.Ref::getName)
                .hasSize(3)
                .contains(TAG_PREFIX + first.name(), TAG_PREFIX + second.name());

        final File cloneDir = gitRepoDir.getRoot().resolve("clone").toFile();
        try (Git clone = Git.cloneRepository()
                            .setURI("http://127.0.0.1:" + dogma.serverAddress().getPort() + '/' +
                                    projectName + '/' + REPOSITORY_NAME + ".git")
                            .setBranch(TAG_PREFIX + second.name())
                            .setDirectory(cloneDir)
                            .setCredentialsProvider(
                                    new UsernamePasswordCredentialsProvider("dogma", "anonymous"))
                            .call()) {
            assertThat(clone.getRepository().resolve(Constants.HEAD)).isEqualTo(secondTag);
            assertThat(new String(Files.readAllBytes(cloneDir.toPath().resolve("encrypted.txt")),
                                  StandardCharsets.UTF_8).trim())
                    .isEqualTo("secret-2");
        }
    }

    @Test
    void preservesHistoryWithoutPublishingTags() throws Exception {
        pushMirrorSettings(false);
        mirroringService.mirror().join();
        final Revision baseline = headRevision();
        commitFile("encrypted.txt", "secret", "Update encrypted repository");

        mirroringService.mirror().join();

        assertThat(headRevision()).isEqualTo(baseline.forward(1));
        assertThat(client.getHistory(projectName, REPOSITORY_NAME, Revision.HEAD,
                                     Revision.HEAD, PathPattern.all(), 0).join().get(0).upstreamCommitId())
                .isNull();
        final Repository repository =
                dogma.projectManager().get(projectName).repos().get(REPOSITORY_NAME).jGitRepository();
        assertThat(repository.getRefDatabase().getRefsByPrefix(Constants.R_TAGS)).isEmpty();
    }

    private RevCommit commitFile(String path, String content, String message) throws Exception {
        final File file = new File(gitWorkTree, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        git.add().addFilepattern(path).call();
        return git.commit().setMessage(message).call();
    }

    private Revision headRevision() {
        return client.normalizeRevision(projectName, REPOSITORY_NAME, Revision.HEAD).join();
    }

    private String fileContent(Revision revision) {
        return client.forRepo(projectName, REPOSITORY_NAME)
                     .file("/encrypted.txt")
                     .get(revision)
                     .join()
                     .contentAsText()
                     .trim();
    }

    private void pushMirrorSettings(boolean publishRemoteCommitTags) {
        final String credentialName = credentialName(projectName, "none");
        client.forRepo(projectName, Project.REPO_DOGMA)
              .commit("Add /credentials/none",
                      Change.ofJsonUpsert(credentialFile(credentialName),
                                          "{ \"type\": \"NONE\", \"name\": \"" + credentialName +
                                          "\", \"enabled\": true }"))
              .push().join();
        final String gitUri = "git+file://" +
                              (gitWorkTree.getPath().startsWith(File.separator) ? "" : "/") +
                              gitWorkTree.getPath().replace(File.separatorChar, '/') + "/.git";
        client.forRepo(projectName, Project.REPO_DOGMA)
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
                              "  \"publishRemoteCommitTags\": " + publishRemoteCommitTags +
                              '}'))
              .push().join();
    }
}
