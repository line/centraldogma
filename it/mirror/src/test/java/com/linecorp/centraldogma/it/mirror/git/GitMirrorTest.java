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

package com.linecorp.centraldogma.it.mirror.git;

import static com.google.common.base.MoreObjects.firstNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_COMMIT_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_GPGSIGN;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.annotation.Nullable;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.MergeResult.MergeStatus;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.internal.JGitUtil;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.internal.TemporaryFolderExtension;
import com.linecorp.centraldogma.testing.internal.TestUtil;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class GitMirrorTest {

    private static final int MAX_NUM_FILES = 32;
    private static final long MAX_NUM_BYTES = 1048576; // 1 MiB

    private static final String REPO_FOO = "foo";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.mirroringEnabled(true);
            builder.maxNumFilesPerMirror(MAX_NUM_FILES);
            builder.maxNumBytesPerMirror(MAX_NUM_BYTES);
        }
    };

    private static CentralDogma client;
    private static MirroringService mirroringService;

    @BeforeAll
    static void init() {
        client = dogma.client();
        mirroringService = dogma.mirroringService();
    }

    @RegisterExtension
    final TemporaryFolderExtension gitRepoDir = new TemporaryFolderExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    private Git git;
    private File gitWorkTree;
    private String gitUri;

    private String projName;

    @BeforeEach
    void initGitRepo(TestInfo testInfo) throws Exception {
        final String repoName = TestUtil.normalizedDisplayName(testInfo);
        gitWorkTree = new File(gitRepoDir.getRoot().toFile(), repoName).getAbsoluteFile();
        final Repository gitRepo = new FileRepositoryBuilder().setWorkTree(gitWorkTree).build();
        createGitRepo(gitRepo);

        git = Git.wrap(gitRepo);
        gitUri = "git+file://" +
                 (gitWorkTree.getPath().startsWith(File.separator) ? "" : "/") +
                 gitWorkTree.getPath().replace(File.separatorChar, '/') +
                 "/.git";

        // Start the master branch with an empty commit.
        git.commit().setMessage("Initial commit").call();
    }

    private static void createGitRepo(Repository gitRepo) throws IOException {
        gitRepo.create();

        // Disable GPG signing.
        final StoredConfig config = gitRepo.getConfig();
        config.setBoolean(CONFIG_COMMIT_SECTION, null, CONFIG_KEY_GPGSIGN, false);
        config.save();
    }

    @BeforeEach
    void initDogmaRepo(TestInfo testInfo) {
        projName = TestUtil.normalizedDisplayName(testInfo);
        client.createProject(projName).join();
        client.createRepository(projName, REPO_FOO).join();
    }

    @AfterEach
    void destroyDogmaRepo() {
        client.removeProject(projName).join();
    }

    @Test
    void remoteToLocal() throws Exception {
        pushMirrorSettings(null, null, null);

        final Revision rev0 = client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();

        // Mirror an empty git repository, which will;
        // - Create /mirror_state.json
        // - Remove the sample files created by createProject().
        mirroringService.mirror().join();

        //// Make sure a new commit is added.
        final Revision rev1 = client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();
        assertThat(rev1).isEqualTo(rev0.forward(1));

        //// Make sure /mirror_state.json exists (and nothing else.)
        final Entry<JsonNode> expectedInitialMirrorState = expectedMirrorState(rev1, "/");
        assertThat(client.getFiles(projName, REPO_FOO, rev1, PathPattern.all()).join().values())
                .containsExactly(expectedInitialMirrorState);

        // Try to mirror again with no changes in the git repository.
        mirroringService.mirror().join();

        //// Make sure it does not try to produce an empty commit.
        final Revision rev2 = client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();
        assertThat(rev2).isEqualTo(rev1);

        // Now, add some files to the git repository and mirror.
        //// This file should not be mirrored because it does not conform to CD's file naming rule.
        addToGitIndex(".gitkeep", "");
        addToGitIndex("first/light.txt", "26-Aug-2014");
        addToGitIndex("second/son.json", "{\"release_date\": \"21-Mar-2014\"}");
        git.commit().setMessage("Add the release dates of the 'Infamous' series")
           .setAuthor("Mirror", "mirror@localhost.localdomain")
           .call();

        mirroringService.mirror().join();
        final List<Commit> commits = client.getHistory(projName, REPO_FOO, Revision.HEAD, Revision.INIT,
                                                       PathPattern.all(), 1)
                                           .join();
        assertThat(commits).isNotEmpty();
        final String detail = commits.get(0).detail();
        assertThat(detail).isNotEmpty()
                          .contains("Author", "Date")
                          .contains("Mirror", "mirror@localhost.localdomain")
                          .contains("Add the release dates of the 'Infamous' series");

        //// Make sure a new commit is added.
        final Revision rev3 = client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();
        assertThat(rev3).isEqualTo(rev2.forward(1));

        //// Make sure the two files are all there.
        final Entry<JsonNode> expectedSecondMirrorState = expectedMirrorState(rev3, "/");
        assertThat(client.getFiles(projName, REPO_FOO, rev3, PathPattern.all()).join().values())
                .containsExactlyInAnyOrder(expectedSecondMirrorState,
                                           Entry.ofDirectory(rev3, "/first"),
                                           Entry.ofText(rev3, "/first/light.txt", "26-Aug-2014\n"),
                                           Entry.ofDirectory(rev3, "/second"),
                                           Entry.ofJson(rev3, "/second/son.json",
                                                        "{\"release_date\": \"21-Mar-2014\"}"));

        // Rewrite the history of the git repository and mirror.
        git.reset().setMode(ResetType.HARD).setRef("HEAD^").call();
        addToGitIndex("final_fantasy_xv.txt", "29-Nov-2016");
        git.commit().setMessage("Add the release date of the 'Final Fantasy XV'").call();

        mirroringService.mirror().join();

        //// Make sure a new commit is added.
        final Revision rev4 = client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();
        assertThat(rev4).isEqualTo(rev3.forward(1));

        //// Make sure the rewritten content is mirrored.
        final Entry<JsonNode> expectedThirdMirrorState = expectedMirrorState(rev4, "/");
        assertThat(client.getFiles(projName, REPO_FOO, rev4, PathPattern.all()).join().values())
                .containsExactlyInAnyOrder(expectedThirdMirrorState,
                                           Entry.ofText(rev4, "/final_fantasy_xv.txt", "29-Nov-2016\n"));
    }

    @Test
    void remoteToLocal_gitignore() throws Exception {
        pushMirrorSettings(null, "/first", "\"/exclude_if_root.txt\\nexclude_dir\"");
        checkGitignore();
    }

    @Test
    void remoteToLocal_gitignore_with_array() throws Exception {
        pushMirrorSettings(null, "/first", "[\"/exclude_if_root.txt\", \"exclude_dir\"]");
        checkGitignore();
    }

    @Test
    void remoteToLocal_subdirectory() throws Exception {
        pushMirrorSettings("/target", "/source/main", null);

        client.forRepo(projName, REPO_FOO)
              .commit("Add a file that's not part of mirror", Change.ofTextUpsert("/not_mirrored.txt", ""))
              .push().join();

        final Revision rev0 = client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();

        // Mirror an empty git repository, which will;
        // - Create /target/mirror_state.json
        // - NOT remove the sample files created by createProject(), because they are not under /target.
        mirroringService.mirror().join();

        //// Make sure a new commit is added.
        final Revision rev1 = client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();
        assertThat(rev1).isEqualTo(rev0.forward(1));

        //// Make sure /target/mirror_state.json exists (and nothing else.)
        final Entry<JsonNode> expectedInitialMirrorState = expectedMirrorState(rev1, "/target/");
        assertThat(client.getFiles(projName, REPO_FOO, rev1, PathPattern.of("/target/**")).join().values())
                .containsExactly(expectedInitialMirrorState);

        // Now, add some files to the git repository and mirror.
        // Note that the files not under '/source' should not be mirrored.
        addToGitIndex("source/main/first/light.txt", "26-Aug-2014"); // mirrored
        addToGitIndex("second/son.json", "{\"release_date\": \"21-Mar-2014\"}"); // not mirrored
        git.commit().setMessage("Add the release dates of the 'Infamous' series").call();

        mirroringService.mirror().join();

        //// Make sure a new commit is added.
        final Revision rev2 = client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();
        assertThat(rev2).isEqualTo(rev1.forward(1));

        //// Make sure 'target/first/light.txt' is mirrored.
        final Entry<JsonNode> expectedSecondMirrorState = expectedMirrorState(rev2, "/target/");
        assertThat(client.getFiles(projName, REPO_FOO, rev2, PathPattern.of("/target/**")).join().values())
                .containsExactlyInAnyOrder(expectedSecondMirrorState,
                                           Entry.ofDirectory(rev2, "/target/first"),
                                           Entry.ofText(rev2, "/target/first/light.txt", "26-Aug-2014\n"));

        //// Make sure the files not under '/target' are not touched. (sample files)
        assertThat(client.getFiles(projName, REPO_FOO, rev2, PathPattern.of("/not_mirrored.txt"))
                         .join()
                         .values())
                .isNotEmpty();
    }

    @Test
    void remoteToLocal_merge() throws Exception {
        pushMirrorSettings(null, null, null);

        // Mirror an empty git repository, which will;
        // - Create /mirror_state.json
        // - Remove the sample files created by createProject().
        mirroringService.mirror().join();

        // Create a text file, modify it in two branches ('master' and 'fork') and merge 'fork' into 'master'.
        addToGitIndex("alphabets.txt", // 'c' and 'x' are missing.
                      "a\nb\nd\ne\nf\ng\nh\ni\nj\nk\nl\nm\nn\no\np\nq\nr\ns\nt\nu\nv\nw\ny\nz\n");
        git.commit().setMessage("Add alphabets.txt").call();

        //// Create a new branch 'fork' and add the missing 'x'.
        git.checkout().setCreateBranch(true).setName("fork").call();
        addToGitIndex("alphabets.txt", // Add the missing 'x'.
                      "a\nb\nd\ne\nf\ng\nh\ni\nj\nk\nl\nm\nn\no\np\nq\nr\ns\nt\nu\nv\nw\nx\ny\nz\n");
        final RevCommit commit1 = git.commit().setMessage("Add missing 'x'").call();

        //// Check out 'master' and add the missing 'c'.
        git.checkout().setName("master").call();
        addToGitIndex("alphabets.txt", // Add the missing 'c'.
                      "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk\nl\nm\nn\no\np\nq\nr\ns\nt\nu\nv\nw\ny\nz\n");
        final RevCommit commit2 = git.commit().setMessage("Add missing 'c'").call();

        //// Merge 'fork' into 'master' to create a merge commit.
        final MergeResult mergeResult = git.merge()
                                           .include(commit1.getId())
                                           .setFastForward(FastForwardMode.NO_FF)
                                           .setMessage("Merge 'fork'").call();

        //// Make sure the merge commit has been added.
        assertThat(mergeResult.getMergeStatus()).isEqualTo(MergeStatus.MERGED);
        final RevCommit lastCommit = git.log().all().call().iterator().next();
        assertThat(lastCommit.getParentCount()).isEqualTo(2);
        assertThat(lastCommit.getParents()).containsExactlyInAnyOrder(commit1, commit2);

        // Run the mirror and ensure alphabets.txt contains all alphabets.
        mirroringService.mirror().join();

        final Revision headRev = client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();
        final Entry<JsonNode> expectedMirrorState = expectedMirrorState(headRev, "/");
        final Entry<String> expectedAlphabets = Entry.ofText(
                headRev,
                "/alphabets.txt",
                "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk\nl\nm\nn\no\np\nq\nr\ns\nt\nu\nv\nw\nx\ny\nz\n");

        assertThat(client.getFiles(projName, REPO_FOO, Revision.HEAD, PathPattern.all()).join().values())
                .containsExactlyInAnyOrder(expectedMirrorState, expectedAlphabets);
    }

    @Test
    void remoteToLocal_submodule(TestInfo testInfo) throws Exception {
        pushMirrorSettings(null, null, null);

        // Create a new repository for a submodule.
        final String submoduleName = TestUtil.normalizedDisplayName(testInfo) + ".submodule";
        final File gitSubmoduleWorkTree =
                new File(gitRepoDir.getRoot().toFile(), submoduleName).getAbsoluteFile();
        final Repository gitSubmoduleRepo =
                new FileRepositoryBuilder().setWorkTree(gitSubmoduleWorkTree).build();
        createGitRepo(gitSubmoduleRepo);
        final Git gitSubmodule = Git.wrap(gitSubmoduleRepo);
        final String gitSubmoduleUri = "file://" +
                                       (gitSubmoduleWorkTree.getPath().startsWith(File.separator) ? "" : "/") +
                                       gitSubmoduleWorkTree.getPath().replace(File.separatorChar, '/') +
                                       "/.git";

        // Prepare the master branch of the submodule repository.
        addToGitIndex(gitSubmodule, gitSubmoduleWorkTree,
                      "in_submodule.txt", "This is a file in a submodule.");
        gitSubmodule.commit().setMessage("Initial commit").call();

        // Add the submodule.
        git.submoduleInit().call();
        git.submoduleAdd().setPath("submodule").setURI(gitSubmoduleUri).call();
        git.commit().setMessage("Add a new submodule").call();

        // Check the files under a submodule do not match nor trigger an 'unknown object' error.
        mirroringService.mirror().join();
        final Revision headRev = client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();
        final Entry<JsonNode> expectedMirrorState = expectedMirrorState(headRev, "/");
        assertThat(client.getFiles(projName, REPO_FOO, Revision.HEAD, PathPattern.all()).join().values())
                .containsExactly(expectedMirrorState);
    }

    @Test
    void remoteToLocal_tooManyFiles() throws Exception {
        pushMirrorSettings(null, null, null);

        // Add more than allowed number of filed.
        for (int i = 0; i <= MAX_NUM_FILES; i++) {
            addToGitIndex(i + ".txt", String.valueOf(i));
        }
        git.commit().setMessage("Add a bunch of numbered files").call();

        // Perform mirroring, which should fail.
        assertThatThrownBy(() -> mirroringService.mirror().join())
                .hasCauseInstanceOf(MirrorException.class)
                .hasMessageContaining("contains more than")
                .hasMessageContaining("file");
    }

    @Test
    void remoteToLocal_tooManyBytes() throws Exception {
        pushMirrorSettings(null, null, null);

        // Add files whose total size exceeds the allowed maximum.
        long remainder = MAX_NUM_BYTES + 1;
        final int defaultFileSize = (int) (MAX_NUM_BYTES / MAX_NUM_FILES * 2);
        for (int i = 0;; i++) {
            final int fileSize;
            if (remainder > defaultFileSize) {
                remainder -= defaultFileSize;
                fileSize = defaultFileSize;
            } else {
                fileSize = (int) remainder;
                remainder = 0;
            }

            addToGitIndex(i + ".txt", Strings.repeat("*", fileSize));

            if (remainder == 0) {
                break;
            }
        }
        git.commit().setMessage("Add a bunch of numbered asterisks").call();

        // Perform mirroring, which should fail.
        assertThatThrownBy(() -> mirroringService.mirror().join())
                .hasCauseInstanceOf(MirrorException.class)
                .hasMessageContaining("contains more than")
                .hasMessageContaining("byte");
    }

    @Test
    void remoteToLocal_cloneDefaultSettings() throws Exception {
        // Perform a mirroring task so that the remote Git repository is fetched into `<dataDir>/_mirrors/`.
        pushMirrorSettings(null, null, null);
        mirroringService.mirror().join();

        // Find the Git config files.
        final List<File> configFiles = Files.list(dogma.dataDir().resolve("_mirrors"))
                                            .map(p -> p.resolve("config"))
                                            .filter(Files::isRegularFile)
                                            .map(Path::toFile)
                                            .collect(ImmutableList.toImmutableList());

        // We should find at least one.
        assertThat(configFiles).isNotEmpty();

        for (File configFile : configFiles) {
            // Load the Git config file.
            final FileBasedConfig config = new FileBasedConfig(configFile, FS.DETECTED);
            config.load();
            final String configText = config.toText();

            // All properties set by JGitUtil must be set already,
            // leading `applyDefaults()` to return `false` (means 'not modified').
            assertThat(JGitUtil.applyDefaults(config))
                    .withFailMessage("A mirror repository has unexpected config value(s): %s\n" +
                                     "actual:\n%s\n\n\n" +
                                     "expected:\n%s\n\n\n",
                                     configFile, configText, config.toText())
                    .isFalse();
        }
    }

    @CsvSource({ "meta", "dogma" })
    @ParameterizedTest
    void cannotMirrorToInternalRepositories(String localRepo) {
        assertThatThrownBy(() -> pushMirrorSettings(localRepo, "/", "/", null))
                .hasCauseInstanceOf(CentralDogmaException.class)
                .hasMessageContaining("invalid localRepo:");
    }

    private void pushMirrorSettings(@Nullable String localPath, @Nullable String remotePath,
                                    @Nullable String gitignore) {
        pushMirrorSettings(REPO_FOO, localPath, remotePath, gitignore);
    }

    private void pushMirrorSettings(String localRepo, @Nullable String localPath, @Nullable String remotePath,
                                    @Nullable String gitignore) {
        final String localPath0 = localPath == null ? "/" : localPath;
        final String remoteUri = gitUri + firstNonNull(remotePath, "");
        client.forRepo(projName, Project.REPO_META)
              .commit("Add /mirrors.json",
                      Change.ofJsonUpsert("/mirrors.json",
                                          "[{" +
                                          "  \"type\": \"single\"," +
                                          "  \"direction\": \"REMOTE_TO_LOCAL\"," +
                                          "  \"localRepo\": \"" + localRepo + "\"," +
                                          "  \"localPath\": \"" + localPath0 + "\"," +
                                          "  \"remoteUri\": \"" + remoteUri + "\"," +
                                          "  \"schedule\": \"0 0 0 1 1 ? 2099\"," +
                                          "  \"gitignore\": " + firstNonNull(gitignore, "\"\"") +
                                          "}]"))
              .push().join();
    }

    private Entry<JsonNode> expectedMirrorState(Revision revision, String localPath) throws IOException {
        final String sha1 = git.getRepository()
                               .exactRef(Constants.R_HEADS + Constants.MASTER)
                               .getObjectId().getName();

        return Entry.ofJson(revision, localPath + "mirror_state.json",
                            "{ \"sourceRevision\": \"" + sha1 + "\" }");
    }

    private void addToGitIndex(String path, String content) throws IOException, GitAPIException {
        addToGitIndex(git, gitWorkTree, path, content);
    }

    static void addToGitIndex(Git git, File gitWorkTree,
                              String path, String content) throws IOException, GitAPIException {
        final File file = Paths.get(gitWorkTree.getAbsolutePath(), path.split("/")).toFile();
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        git.add().addFilepattern(path).call();
    }

    private void checkGitignore() throws IOException, GitAPIException {
        final Revision rev0 = client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();

        // Mirror an empty git repository, which will;
        // - Create /mirror_state.json
        // - Remove the sample files created by createProject().
        mirroringService.mirror().join();

        //// Make sure a new commit is added.
        final Revision rev1 = client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();
        assertThat(rev1).isEqualTo(rev0.forward(1));

        //// Make sure /mirror_state.json exists (and nothing else.)
        final Entry<JsonNode> expectedInitialMirrorState = expectedMirrorState(rev1, "/");
        assertThat(client.getFiles(projName, REPO_FOO, rev1, "/**").join().values())
                .containsExactly(expectedInitialMirrorState);

        // Try to mirror again with no changes in the git repository.
        mirroringService.mirror().join();

        //// Make sure it does not try to produce an empty commit.
        final Revision rev2 = client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();
        assertThat(rev2).isEqualTo(rev1);

        // Now, add some files to the git repository and mirror.
        addToGitIndex(".gitkeep", "");
        addToGitIndex("first/light.txt", "26-Aug-2014");

        /// This file is excluded from mirroring by pattern "/exclude_if_root.txt"
        addToGitIndex("first/exclude_if_root.txt", "26-Aug-2014");

        addToGitIndex("first/subdir/exclude_if_root.txt", "26-Aug-2014");

        /// This file is excluded from mirroring by pattern "exclude_dir"
        addToGitIndex("first/subdir/exclude_dir/cascaded_exclude.txt", "26-Aug-2014");

        git.commit().setMessage("Add the release dates of the 'Infamous' series").call();

        mirroringService.mirror().join();

        //// Make sure a new commit is added.
        final Revision rev3 = client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();
        assertThat(rev3).isEqualTo(rev2.forward(1));

        //// Make sure the file that match gitignore are not mirrored.
        final Entry<JsonNode> expectedSecondMirrorState = expectedMirrorState(rev3, "/");
        assertThat(client.getFiles(projName, REPO_FOO, rev3, "/**").join().values())
                .containsExactlyInAnyOrder(expectedSecondMirrorState,
                                           Entry.ofText(rev3, "/light.txt", "26-Aug-2014\n"),
                                           Entry.ofDirectory(rev3, "/subdir"),
                                           Entry.ofText(rev3, "/subdir/exclude_if_root.txt", "26-Aug-2014\n"));

        /// Add new file, but it is not mirrored because its parent directory is excluded
        addToGitIndex("first/subdir/exclude_dir/new2.txt", "26-Aug-2014");

        git.commit().setMessage("Add new file in excluded directory").call();

        mirroringService.mirror().join();

        final Revision rev4 = client.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();
        assertThat(rev4).isEqualTo(rev2.forward(2));

        //// Make sure the file that there's no change in mirrored file list
        assertThat(client.getFiles(projName, REPO_FOO, rev4, "/**").join().values())
                .containsExactlyInAnyOrder(expectedMirrorState(rev4, "/"),
                                           Entry.ofText(rev4, "/light.txt", "26-Aug-2014\n"),
                                           Entry.ofDirectory(rev4, "/subdir"),
                                           Entry.ofText(rev4, "/subdir/exclude_if_root.txt", "26-Aug-2014\n"));
    }
}
