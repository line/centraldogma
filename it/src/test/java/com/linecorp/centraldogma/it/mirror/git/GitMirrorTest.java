/*
 * Copyright 2017 LINE Corporation
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
import static com.linecorp.centraldogma.server.internal.storage.project.Project.REPO_MAIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

import javax.annotation.Nullable;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.MergeResult.MergeStatus;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Strings;
import com.google.common.io.Files;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.it.TestConstants;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class GitMirrorTest {

    private static final int MAX_NUM_FILES = 32;
    private static final long MAX_NUM_BYTES = 1048576; // 1 MiB

    @ClassRule
    public static final CentralDogmaRule rule = new CentralDogmaRule() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.mirroringEnabled(true);
            builder.maxNumFilesPerMirror(MAX_NUM_FILES);
            builder.maxNumBytesPerMirror(MAX_NUM_BYTES);
        }
    };

    private static CentralDogma client;
    private static MirroringService mirroringService;

    @BeforeClass
    public static void init() {
        client = rule.client();
        mirroringService = rule.mirroringService();
    }

    @Rule
    public final TemporaryFolder gitRepoDir = new TemporaryFolder();

    @Rule
    public final TestName testName = new TestName();

    private Git git;
    private File gitWorkTree;
    private String gitUri;

    private String projName;

    @Before
    public void initGitRepo() throws Exception {
        gitWorkTree = new File(gitRepoDir.getRoot(), testName.getMethodName()).getAbsoluteFile();
        final Repository gitRepo = new FileRepositoryBuilder().setWorkTree(gitWorkTree).build();
        gitRepo.create();
        git = Git.wrap(gitRepo);
        gitUri = "git+file://" +
                 (gitWorkTree.getPath().startsWith(File.separator) ? "" : "/") +
                 gitWorkTree.getPath().replace(File.separatorChar, '/') +
                 "/.git";

        // Start the master branch with an empty commit.
        git.commit().setMessage("Initial commit").call();
    }

    @Before
    public void initDogmaRepo() throws Exception {
        projName = testName.getMethodName();
        client.createProject(projName).join();
    }

    @After
    public void destroyDogmaRepo() {
        client.removeProject(projName).join();
    }

    @Test
    public void remoteToLocal() throws Exception {
        final Entry<JsonNode> expectedInitialMirrorState = expectedMirrorState("/");

        pushMirrorSettings(null, null);

        final Revision rev0 = client.normalizeRevision(projName, REPO_MAIN, Revision.HEAD).join();

        // Mirror an empty git repository, which will;
        // - Create /mirror_state.json
        // - Remove the sample files created by createProject().
        mirroringService.mirror().join();

        //// Make sure a new commit is added.
        final Revision rev1 = client.normalizeRevision(projName, REPO_MAIN, Revision.HEAD).join();
        assertThat(rev1).isEqualTo(rev0.forward(1));

        //// Make sure /mirror_state.json exists (and nothing else.)
        assertThat(client.getFiles(projName, REPO_MAIN, rev1, "/**").join().values())
                .containsExactly(expectedInitialMirrorState);

        // Try to mirror again with no changes in the git repository.
        mirroringService.mirror().join();

        //// Make sure it does not try to produce an empty commit.
        final Revision rev2 = client.normalizeRevision(projName, REPO_MAIN, Revision.HEAD).join();
        assertThat(rev2).isEqualTo(rev1);

        // Now, add some files to the git repository and mirror.
        //// This file should not be mirrored because it does not conform to CD's file naming rule.
        addToGitIndex(".gitkeep", "");
        addToGitIndex("first/light.txt", "26-Aug-2014");
        addToGitIndex("second/son.json", "{\"release_date\": \"21-Mar-2014\"}");
        git.commit().setMessage("Add the release dates of the 'Infamous' series").call();

        final Entry<JsonNode> expectedSecondMirrorState = expectedMirrorState("/");
        mirroringService.mirror().join();

        //// Make sure a new commit is added.
        final Revision rev3 = client.normalizeRevision(projName, REPO_MAIN, Revision.HEAD).join();
        assertThat(rev3).isEqualTo(rev2.forward(1));

        //// Make sure the two files are all there.
        assertThat(client.getFiles(projName, REPO_MAIN, rev3, "/**").join().values())
                .containsExactlyInAnyOrder(expectedSecondMirrorState,
                                           Entry.ofDirectory("/first"),
                                           Entry.ofText("/first/light.txt", "26-Aug-2014\n"),
                                           Entry.ofDirectory("/second"),
                                           Entry.ofJson("/second/son.json",
                                                        "{\"release_date\": \"21-Mar-2014\"}"));

        // Rewrite the history of the git repository and mirror.
        git.reset().setMode(ResetType.HARD).setRef("HEAD^").call();
        addToGitIndex("final_fantasy_xv.txt", "29-Nov-2016");
        git.commit().setMessage("Add the release date of the 'Final Fantasy XV'").call();

        final Entry<JsonNode> expectedThirdMirrorState = expectedMirrorState("/");
        mirroringService.mirror().join();

        //// Make sure a new commit is added.
        final Revision rev4 = client.normalizeRevision(projName, REPO_MAIN, Revision.HEAD).join();
        assertThat(rev4).isEqualTo(rev3.forward(1));

        //// Make sure the rewritten content is mirrored.
        assertThat(client.getFiles(projName, REPO_MAIN, rev4, "/**").join().values())
                .containsExactlyInAnyOrder(expectedThirdMirrorState,
                                           Entry.ofText("/final_fantasy_xv.txt", "29-Nov-2016\n"));
    }

    @Test
    public void remoteToLocal_subdirectory() throws Exception {
        final Entry<JsonNode> expectedInitialMirrorState = expectedMirrorState("/target/");

        pushMirrorSettings("/target", "/source/main");

        final Revision rev0 = client.normalizeRevision(projName, REPO_MAIN, Revision.HEAD).join();

        // Mirror an empty git repository, which will;
        // - Create /target/mirror_state.json
        // - NOT remove the sample files created by createProject(), because they are not under /target.
        mirroringService.mirror().join();

        //// Make sure a new commit is added.
        final Revision rev1 = client.normalizeRevision(projName, REPO_MAIN, Revision.HEAD).join();
        assertThat(rev1).isEqualTo(rev0.forward(1));

        //// Make sure /target/mirror_state.json exists (and nothing else.)
        assertThat(client.getFiles(projName, REPO_MAIN, rev1, "/target/**").join().values())
                .containsExactly(expectedInitialMirrorState);

        // Now, add some files to the git repository and mirror.
        // Note that the files not under '/source' should not be mirrored.
        addToGitIndex("source/main/first/light.txt", "26-Aug-2014"); // mirrored
        addToGitIndex("second/son.json", "{\"release_date\": \"21-Mar-2014\"}"); // not mirrored
        git.commit().setMessage("Add the release dates of the 'Infamous' series").call();

        final Entry<JsonNode> expectedSecondMirrorState = expectedMirrorState("/target/");
        mirroringService.mirror().join();

        //// Make sure a new commit is added.
        final Revision rev2 = client.normalizeRevision(projName, REPO_MAIN, Revision.HEAD).join();
        assertThat(rev2).isEqualTo(rev1.forward(1));

        //// Make sure 'target/first/light.txt' is mirrored.
        assertThat(client.getFiles(projName, REPO_MAIN, rev2, "/target/**").join().values())
                .containsExactlyInAnyOrder(expectedSecondMirrorState,
                                           Entry.ofDirectory("/target/first"),
                                           Entry.ofText("/target/first/light.txt", "26-Aug-2014\n"));

        //// Make sure the files not under '/target' are not touched. (sample files)
        assertThat(client.getFiles(projName, REPO_MAIN, rev2, "/samples/**").join().values())
                .isNotEmpty();
    }

    @Test
    public void remoteToLocal_merge() throws Exception {
        pushMirrorSettings(null, null);

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

        final Entry<JsonNode> expectedMirrorState = expectedMirrorState("/");
        final Entry<String> expectedAlphabets = Entry.ofText(
                "/alphabets.txt",
                "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk\nl\nm\nn\no\np\nq\nr\ns\nt\nu\nv\nw\nx\ny\nz\n");

        assertThat(client.getFiles(projName, REPO_MAIN, Revision.HEAD, "/**").join().values())
                .containsExactlyInAnyOrder(expectedMirrorState, expectedAlphabets);
    }

    @Test
    public void remoteToLocal_tooManyFiles() throws Exception {
        pushMirrorSettings(null, null);

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
    public void remoteToLocal_tooManyBytes() throws Exception {
        pushMirrorSettings(null, null);

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

    private void pushMirrorSettings(@Nullable String localPath, @Nullable String remotePath) {
        client.push(projName, Project.REPO_META, Revision.HEAD, TestConstants.AUTHOR, "Add /mirrors.json",
                    Change.ofJsonUpsert("/mirrors.json",
                                        "[{" +
                                        "  \"type\": \"single\"," +
                                        "  \"direction\": \"REMOTE_TO_LOCAL\"," +
                                        "  \"localRepo\": \"" + REPO_MAIN + "\"," +
                                        (localPath != null ? "\"localPath\": \"" + localPath + "\"," : "") +
                                        "  \"remoteUri\": \"" + gitUri + firstNonNull(remotePath, "") + '"' +
                                        "}]")).join();
    }

    private Entry<JsonNode> expectedMirrorState(String localPath) throws IOException {
        final String sha1 = git.getRepository()
                               .exactRef(Constants.R_HEADS + Constants.MASTER)
                               .getObjectId().getName();

        return Entry.ofJson(localPath + "mirror_state.json", "{ \"sourceRevision\": \"" + sha1 + "\" }");
    }

    private void addToGitIndex(String path, String content) throws IOException, GitAPIException {
        final File file = Paths.get(gitWorkTree.getAbsolutePath(), path.split("/")).toFile();
        file.getParentFile().mkdirs();
        Files.asCharSink(file, StandardCharsets.UTF_8).write(content);
        git.add().addFilepattern(path).call();
    }
}
