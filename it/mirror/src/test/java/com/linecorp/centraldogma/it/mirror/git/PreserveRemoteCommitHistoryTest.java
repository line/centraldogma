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
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_COMMIT_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_GPGSIGN;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
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
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.internal.TemporaryFolderExtension;
import com.linecorp.centraldogma.testing.internal.TestUtil;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class PreserveRemoteCommitHistoryTest {

    private static final String REPO_FOO = "foo";
    private static final String TAG_PREFIX = "refs/tags/dogma-";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.pluginConfigs(new MirroringServicePluginConfig(true, 1, 32, 1048576L, false));
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
    private String gitUri;
    private String projName;

    @BeforeAll
    static void init() {
        client = dogma.client();
        mirroringService = dogma.mirroringService();
    }

    @BeforeEach
    void initGitRepo(TestInfo testInfo) throws Exception {
        final String repoName = TestUtil.normalizedDisplayName(testInfo);
        gitWorkTree = new File(gitRepoDir.getRoot().toFile(), repoName).getAbsoluteFile();
        final Repository gitRepo = new FileRepositoryBuilder().setWorkTree(gitWorkTree).build();
        gitRepo.create();
        final StoredConfig config = gitRepo.getConfig();
        config.setBoolean(CONFIG_COMMIT_SECTION, null, CONFIG_KEY_GPGSIGN, false);
        config.save();

        git = Git.wrap(gitRepo);
        gitUri = "git+file://" +
                 (gitWorkTree.getPath().startsWith(File.separator) ? "" : "/") +
                 gitWorkTree.getPath().replace(File.separatorChar, '/') + "/.git";
        git.commit().setMessage("Initial commit").call();
    }

    @BeforeEach
    void initDogmaRepo(TestInfo testInfo) {
        projName = TestUtil.normalizedDisplayName(testInfo);
        client.createProject(projName).join();
        client.createRepository(projName, REPO_FOO).join();
    }

    @AfterEach
    void destroyDogmaRepo() {
        git.close();
        // Remove the project so later mirror runs cannot reference this test's temporary Git directory.
        client.removeProject(projName).join();
    }

    @Test
    void firstRunPushesASingleSnapshot() throws Exception {
        final RevCommit first = commitFile("a.txt", "1", "Add a");
        final RevCommit head = commitFile("b.txt", "2", "Add b");

        pushMirrorSettings(true);
        final Revision before = headRevision();
        mirroringService.mirror().join();

        // Nothing to replay from, so the whole remote history collapses into one revision.
        assertThat(headRevision()).isEqualTo(before.forward(1));
        assertThat(fileContent(headRevision(), "/a.txt")).isEqualTo("1");
        assertThat(fileContent(headRevision(), "/b.txt")).isEqualTo("2");
        assertThat(tagNames()).doesNotContain(TAG_PREFIX + first.name());
        assertThat(tagNames()).contains(TAG_PREFIX + head.name());
        assertThat(history(headRevision(), headRevision()).get(0).upstreamCommitId()).isEqualTo(head.name());
    }

    @Test
    void replaysEachRemoteCommit() throws Exception {
        pushMirrorSettings(true);
        mirroringService.mirror().join();
        final Revision baseline = headRevision();

        final RevCommit first = commitFile("a.txt", "1", "Add a", "Alice", "alice@example.com");
        final RevCommit second = commitFile("b.txt", "2", "Add b", "Bob", "bob@example.com");
        final RevCommit third = commitFile("c.txt", "3", "Add c", "Carol", "carol@example.com");

        mirroringService.mirror().join();

        // One revision per remote commit.
        assertThat(headRevision()).isEqualTo(baseline.forward(3));

        // Each revision holds exactly what the matching remote commit held.
        assertThat(fileContent(baseline.forward(1), "/a.txt")).isEqualTo("1");
        assertThat(client.getFiles(projName, REPO_FOO, baseline.forward(1), PathPattern.all()).join())
                .doesNotContainKey("/b.txt");
        assertThat(fileContent(baseline.forward(2), "/b.txt")).isEqualTo("2");
        assertThat(fileContent(baseline.forward(3), "/c.txt")).isEqualTo("3");

        // The remote subject and author are carried over.
        final List<Commit> commits = history(baseline.forward(3), baseline.forward(1));
        assertThat(commits).extracting(Commit::summary)
                           .containsExactly("Add c", "Add b", "Add a");
        assertThat(commits).extracting(commit -> commit.author().email())
                           .containsExactly("carol@example.com", "bob@example.com", "alice@example.com");

        // Every revision records and is tagged with the remote commit it came from.
        assertThat(commits).extracting(Commit::upstreamCommitId)
                           .containsExactly(third.name(), second.name(), first.name());
        assertThat(commits).extracting(Commit::commitId).doesNotContainNull();
        assertThat(tagObjectId(third.name()).name()).isEqualTo(commits.get(0).commitId());
        assertThat(tagObjectId(second.name()).name()).isEqualTo(commits.get(1).commitId());
        assertThat(tagObjectId(first.name()).name()).isEqualTo(commits.get(2).commitId());
        assertThat(tagNames()).contains(TAG_PREFIX + first.name(),
                                        TAG_PREFIX + second.name(),
                                        TAG_PREFIX + third.name());
    }

    @Test
    void remoteCommitOutsideMirroredPathStillCreatesARevision() throws Exception {
        commitFile("config/value.txt", "1", "Add config");
        pushMirrorSettings(true, "/", "/config/");
        mirroringService.mirror().join();
        final Revision baseline = headRevision();

        final RevCommit outside = commitFile("outside.txt", "2", "Update outside path");
        mirroringService.mirror().join();

        assertThat(headRevision()).isEqualTo(baseline.forward(1));
        assertThat(fileContent(headRevision(), "/value.txt")).isEqualTo("1");
        final Commit mirrored = history(headRevision(), headRevision()).get(0);
        assertThat(mirrored.upstreamCommitId()).isEqualTo(outside.name());
        assertThat(tagObjectId(outside.name()).name()).isEqualTo(mirrored.commitId());
    }

    @Test
    void exactlyOneHundredCommitsAreReplayed() throws Exception {
        pushMirrorSettings(true);
        mirroringService.mirror().join();
        final Revision baseline = headRevision();
        RevCommit first = null;
        RevCommit last = null;
        for (int i = 0; i < 100; i++) {
            last = commitFile("counter.txt", Integer.toString(i), "Update counter " + i);
            if (first == null) {
                first = last;
            }
        }

        mirroringService.mirror().join();

        assertThat(headRevision()).isEqualTo(baseline.forward(100));
        assertThat(tagNames()).contains(TAG_PREFIX + first.name(), TAG_PREFIX + last.name());
    }

    @Test
    void wideMergeGraphFallsBackToSingleSnapshot() throws Exception {
        pushMirrorSettings(true);
        mirroringService.mirror().join();
        final Revision baseline = headRevision();
        final RevCommit base = git.log().setMaxCount(1).call().iterator().next();

        git.checkout().setCreateBranch(true).setName("left").setStartPoint(base).call();
        RevCommit left = null;
        for (int i = 0; i < 50; i++) {
            left = commitFile("left.txt", Integer.toString(i), "Update left " + i);
        }

        git.checkout().setCreateBranch(true).setName("right").setStartPoint(base).call();
        RevCommit right = null;
        for (int i = 0; i < 50; i++) {
            right = commitFile("right.txt", Integer.toString(i), "Update right " + i);
        }

        git.checkout().setName("master").call();
        MergeResult result = git.merge().include(left).setFastForward(FastForwardMode.NO_FF)
                                .setMessage("Merge left").call();
        assertThat(result.getMergeStatus().isSuccessful()).isTrue();
        result = git.merge().include(right).setFastForward(FastForwardMode.NO_FF)
                    .setMessage("Merge right").call();
        assertThat(result.getMergeStatus().isSuccessful()).isTrue();
        final RevCommit mergedHead = git.log().setMaxCount(1).call().iterator().next();

        mirroringService.mirror().join();

        assertThat(headRevision()).isEqualTo(baseline.forward(1));
        assertThat(fileContent(headRevision(), "/left.txt")).isEqualTo("49");
        assertThat(fileContent(headRevision(), "/right.txt")).isEqualTo("49");
        assertThat(history(headRevision(), headRevision()).get(0).upstreamCommitId())
                .isEqualTo(mergedHead.name());
        assertThat(tagNames()).contains(TAG_PREFIX + mergedHead.name()).hasSize(2);
    }

    @Test
    void tagsAreWrittenIntoPackedRefsOnly() throws Exception {
        pushMirrorSettings(true);
        mirroringService.mirror().join();
        final RevCommit commit = commitFile("a.txt", "1", "Add a");
        mirroringService.mirror().join();

        final Path repoDir = dogma.dataDir().resolve(projName).resolve(REPO_FOO);
        final String packedRefs = new String(Files.readAllBytes(repoDir.resolve("packed-refs")),
                                             StandardCharsets.UTF_8);
        assertThat(packedRefs).contains(TAG_PREFIX + commit.name());

        // Keep immutable tags packed so they do not recreate the loose-ref scaling issue from #104.
        final Path looseTags = repoDir.resolve("refs").resolve("tags");
        assertThat(Files.notExists(looseTags) || isEmptyDirectory(looseTags)).isTrue();

        // The tag transaction must not disturb the branch Central Dogma rewrites on every push.
        assertThat(Files.readAllLines(repoDir.resolve("refs").resolve("heads").resolve("master")))
                .isNotEmpty();
    }

    @Test
    void upstreamTagsAreImmutable() throws Exception {
        pushMirrorSettings(true);
        mirroringService.mirror().join();
        final RevCommit upstream = commitFile("a.txt", "1", "Add a");
        mirroringService.mirror().join();
        final ObjectId originalTag = tagObjectId(upstream.name());

        dogma.projectManager().get(projName).repos().get(REPO_FOO)
             .commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                     "Unrelated change", "", Markup.PLAINTEXT,
                     List.of(Change.ofTextUpsert("/other.txt", "2")), true, upstream.name())
             .join();

        assertThat(tagObjectId(upstream.name())).isEqualTo(originalTag);
        assertThat(history(headRevision(), headRevision()).get(0).commitId())
                .isNotEqualTo(originalTag.name());
    }

    @Test
    void reconfiguredSnapshotDoesNotClaimAnExistingUpstreamTag() throws Exception {
        final RevCommit upstream = commitFile("a.txt", "1", "Add a");
        pushMirrorSettings(true);
        mirroringService.mirror().join();
        final Revision baseline = headRevision();
        final ObjectId originalTag = tagObjectId(upstream.name());

        pushMirrorSettings(true, "/nested/");
        mirroringService.mirror().join();

        assertThat(headRevision()).isEqualTo(baseline.forward(1));
        assertThat(fileContent(headRevision(), "/nested/a.txt")).isEqualTo("1");
        assertThat(history(headRevision(), headRevision()).get(0).upstreamCommitId()).isNull();
        assertThat(tagObjectId(upstream.name())).isEqualTo(originalTag);
    }

    @Test
    void tagCanBeClonedAndCheckedOutOverGitHttp() throws Exception {
        pushMirrorSettings(true);
        mirroringService.mirror().join();
        final RevCommit upstream = commitFile("a.txt", "pinned", "Add a");
        mirroringService.mirror().join();
        final Commit mirrored = history(headRevision(), headRevision()).get(0);

        final File cloneDir = gitRepoDir.getRoot().resolve("clone").toFile();
        try (Git clone = Git.cloneRepository()
                            .setURI("http://127.0.0.1:" + dogma.serverAddress().getPort() + '/' +
                                    projName + '/' + REPO_FOO + ".git")
                            .setBranch(TAG_PREFIX + upstream.name())
                            .setDirectory(cloneDir)
                            .setCredentialsProvider(
                                    new UsernamePasswordCredentialsProvider("dogma", "anonymous"))
                            .call()) {
            assertThat(clone.getRepository().resolve(Constants.HEAD).name())
                    .isEqualTo(mirrored.commitId());
            assertThat(clone.getRepository().exactRef(TAG_PREFIX + upstream.name()).getObjectId().name())
                    .isEqualTo(mirrored.commitId());
            assertThat(new String(Files.readAllBytes(cloneDir.toPath().resolve("a.txt")),
                                  StandardCharsets.UTF_8).trim())
                    .isEqualTo("pinned");
        }
    }

    @Test
    void resetProducesASingleReconciliationRevision() throws Exception {
        pushMirrorSettings(true);
        mirroringService.mirror().join();
        commitFile("a.txt", "1", "Add a");
        commitFile("b.txt", "2", "Add b");
        mirroringService.mirror().join();
        final Revision replayed = headRevision();

        // A rewind can only be represented by one new snapshot revision.
        git.reset().setMode(ResetType.HARD).setRef("HEAD~1").call();
        final RevCommit rewoundHead = git.log().setMaxCount(1).call().iterator().next();
        final ObjectId originalTag = tagObjectId(rewoundHead.name());
        mirroringService.mirror().join();

        assertThat(headRevision()).isEqualTo(replayed.forward(1));
        assertThat(fileContent(headRevision(), "/a.txt")).isEqualTo("1");
        assertThat(client.getFiles(projName, REPO_FOO, headRevision(), PathPattern.all()).join())
                .doesNotContainKey("/b.txt");
        assertThat(history(headRevision(), headRevision()).get(0).upstreamCommitId()).isNull();
        assertThat(tagObjectId(rewoundHead.name())).isEqualTo(originalTag);
    }

    @Test
    void divergedRemoteProducesASingleRevision() throws Exception {
        pushMirrorSettings(true);
        mirroringService.mirror().join();
        commitFile("a.txt", "1", "Add a");
        commitFile("b.txt", "2", "Add b");
        mirroringService.mirror().join();
        final Revision replayed = headRevision();

        // Rewrite history: drop the tip and commit something else in its place.
        git.reset().setMode(ResetType.HARD).setRef("HEAD~1").call();
        final RevCommit divergedHead = commitFile("c.txt", "3", "Add c");
        mirroringService.mirror().join();

        assertThat(headRevision()).isEqualTo(replayed.forward(1));
        assertThat(fileContent(headRevision(), "/c.txt")).isEqualTo("3");
        assertThat(client.getFiles(projName, REPO_FOO, headRevision(), PathPattern.all()).join())
                .doesNotContainKey("/b.txt");
        final Commit mirrored = history(headRevision(), headRevision()).get(0);
        assertThat(mirrored.upstreamCommitId()).isEqualTo(divergedHead.name());
        assertThat(tagObjectId(divergedHead.name()).name()).isEqualTo(mirrored.commitId());
    }

    @Test
    void optionOffKeepsSquashing() throws Exception {
        pushMirrorSettings(false);
        mirroringService.mirror().join();
        final Revision baseline = headRevision();

        commitFile("a.txt", "1", "Add a");
        commitFile("b.txt", "2", "Add b");
        mirroringService.mirror().join();

        assertThat(headRevision()).isEqualTo(baseline.forward(1));
        assertThat(tagNames()).isEmpty();
        assertThat(history(headRevision(), headRevision())).allSatisfy(
                commit -> assertThat(commit.upstreamCommitId()).isNull());
    }

    private static boolean isEmptyDirectory(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> children = Files.list(dir)) {
            return !children.findAny().isPresent();
        }
    }

    private List<String> tagNames() throws IOException {
        final Path repoDir = dogma.dataDir().resolve(projName).resolve(REPO_FOO);
        try (Repository repository = new FileRepositoryBuilder().setGitDir(repoDir.toFile()).build()) {
            return repository.getRefDatabase().getRefsByPrefix("refs/tags/").stream()
                             .map(org.eclipse.jgit.lib.Ref::getName)
                             .collect(com.google.common.collect.ImmutableList.toImmutableList());
        }
    }

    private ObjectId tagObjectId(String upstreamCommitId) throws IOException {
        final Path repoDir = dogma.dataDir().resolve(projName).resolve(REPO_FOO);
        try (Repository repository = new FileRepositoryBuilder().setGitDir(repoDir.toFile()).build()) {
            return repository.exactRef(TAG_PREFIX + upstreamCommitId).getObjectId();
        }
    }

    private Revision headRevision() {
        return client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();
    }

    private String fileContent(Revision revision, String path) {
        return client.forRepo(projName, REPO_FOO)
                     .file(path)
                     .get(revision)
                     .join()
                     .contentAsText()
                     .trim();
    }

    private List<Commit> history(Revision from, Revision to) {
        return client.getHistory(projName, REPO_FOO, from, to, PathPattern.all(), 0).join();
    }

    private RevCommit commitFile(String path, String content, String message) throws Exception {
        return commitFile(path, content, message, "Mirror", "mirror@localhost.localdomain");
    }

    private RevCommit commitFile(String path, String content, String message,
                                 String authorName, String authorEmail) throws Exception {
        final File file = new File(gitWorkTree, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        git.add().addFilepattern(path).call();
        return git.commit().setMessage(message).setAuthor(authorName, authorEmail).call();
    }

    private void pushMirrorSettings(boolean preserveRemoteCommitHistory) {
        final String credentialName = credentialName(projName, "none");
        client.forRepo(projName, Project.REPO_DOGMA)
              .commit("Add /credentials/none",
                      Change.ofJsonUpsert(credentialFile(credentialName),
                                          "{ \"type\": \"NONE\", \"name\": \"" + credentialName +
                                          "\", \"enabled\": true }"))
              .push().join();
        pushMirrorSettings(preserveRemoteCommitHistory, "/");
    }

    private void pushMirrorSettings(boolean preserveRemoteCommitHistory, String localPath) {
        pushMirrorSettings(preserveRemoteCommitHistory, localPath, "/");
    }

    private void pushMirrorSettings(boolean preserveRemoteCommitHistory, String localPath,
                                    String remotePath) {
        final String credentialName = credentialName(projName, "none");
        final String remoteUri = "/".equals(remotePath) ? gitUri : gitUri + remotePath;
        client.forRepo(projName, Project.REPO_DOGMA)
              .commit("Add a mirror",
                      Change.ofJsonUpsert("/repos/" + REPO_FOO + "/mirrors/foo.json",
                                          '{' +
                                          "  \"id\": \"foo\"," +
                                          "  \"enabled\": true," +
                                          "  \"direction\": \"REMOTE_TO_LOCAL\"," +
                                          "  \"localRepo\": \"" + REPO_FOO + "\"," +
                                          "  \"localPath\": \"" + localPath + "\"," +
                                          "  \"remoteUri\": \"" + remoteUri + "\"," +
                                          "  \"schedule\": \"0 0 0 1 1 ? 2099\"," +
                                          "  \"credentialName\": \"" + credentialName + "\"," +
                                          "  \"preserveRemoteCommitHistory\": " +
                                          preserveRemoteCommitHistory +
                                          '}'))
              .push().join();
    }
}
