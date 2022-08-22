/*
 * Copyright 2022 LINE Corporation
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
import static com.linecorp.centraldogma.it.mirror.git.GitMirrorTest.addToGitIndex;
import static com.linecorp.centraldogma.server.internal.mirror.GitMirror.LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_COMMIT_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_GPGSIGN;
import static org.eclipse.jgit.lib.Constants.R_HEADS;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.base.Strings;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.internal.mirror.MirrorState;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.internal.TestUtil;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class LocalToRemoteGitMirrorTest {

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

    @TempDir
    File gitRepoDir;

    private Git git;
    private File gitWorkTree;
    private String gitUri;

    private String projName;

    @BeforeEach
    void initGitRepo(TestInfo testInfo) throws Exception {
        final String repoName = TestUtil.normalizedDisplayName(testInfo);
        gitWorkTree = new File(gitRepoDir, repoName).getAbsoluteFile();
        final Repository gitRepo = new FileRepositoryBuilder().setWorkTree(gitWorkTree).build();
        createGitRepo(gitRepo);

        git = Git.wrap(gitRepo);
        gitUri = "git+file://" +
                 (gitWorkTree.getPath().startsWith(File.separator) ? "" : '/') +
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
        client.purgeProject(projName).join();
    }

    @ParameterizedTest
    @CsvSource({
            "'', ''",
            "/local/foo, /remote",
            "/local, /remote/foo",
            "/local/foo, /remote/foo"
    })
    void localToRemote(String localPath, String remotePath) throws Exception {
        pushMirrorSettings(localPath, remotePath, null);

        final ObjectId commitId = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(getFileContent(commitId, remotePath + '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME))
                .isNull();
        // Mirror an empty Central Dogma repository, which will;
        // - Create /.mirror_state.json
        mirroringService.mirror().join();

        final ObjectId commitId1 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId).isNotEqualTo(commitId1);
        byte[] content = getFileContent(commitId1, remotePath + '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        MirrorState mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("1");

        // Mirror once again without adding a commit.
        mirroringService.mirror().join();

        // Make sure no commit was added thus the source revision wasn't changed.
        final ObjectId commitId2 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId2).isEqualTo(commitId1);
        content = getFileContent(commitId2, remotePath + '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("1");

        // Create a new commit
        client.forRepo(projName, REPO_FOO)
              .commit("Add a commit",
                      Change.ofJsonUpsert(localPath + "/foo.json", "{\"a\":\"b\"}"),
                      Change.ofJsonUpsert(localPath + "/bar/foo.json", "{\"a\":\"c\"}"),
                      Change.ofTextUpsert(localPath + "/baz/foo.txt", "\"a\": \"b\"\n"))
              .push().join();

        mirroringService.mirror().join();
        final ObjectId commitId3 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId3).isNotEqualTo(commitId2);
        content = getFileContent(commitId3, remotePath + '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("2");
        assertThat(Jackson.writeValueAsString(Jackson.readTree(
                getFileContent(commitId3, remotePath + "/foo.json")))).isEqualTo("{\"a\":\"b\"}");
        assertThat(Jackson.writeValueAsString(Jackson.readTree(
                getFileContent(commitId3, remotePath + "/bar/foo.json")))).isEqualTo("{\"a\":\"c\"}");
        assertThat(new String(getFileContent(commitId3, remotePath + "/baz/foo.txt")))
                .isEqualTo("\"a\": \"b\"\n");

        // Mirror once again without adding a commit.
        mirroringService.mirror().join();

        // Make sure no commit was added thus the source revision wasn't changed.
        final ObjectId commitId4 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId4).isEqualTo(commitId3);
        content = getFileContent(commitId4, remotePath + '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("2");

        // Create a new commit
        client.forRepo(projName, REPO_FOO)
              .commit("Remove foo.json and foo.txt",
                      Change.ofRemoval(localPath + "/foo.json"),
                      Change.ofRemoval(localPath + "/baz/foo.txt"))
              .push().join();

        mirroringService.mirror().join();
        final ObjectId commitId5 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId5).isNotEqualTo(commitId4);
        content = getFileContent(commitId5, remotePath + '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("3");
        assertThat(getFileContent(commitId5, remotePath + "/foo.json")).isNull();
        assertThat(getFileContent(commitId5, remotePath + "/baz/foo.txt")).isNull();
        assertThat(Jackson.writeValueAsString(Jackson.readTree(
                getFileContent(commitId5, remotePath + "/bar/foo.json")))).isEqualTo("{\"a\":\"c\"}");

        addToGitIndex(git, gitWorkTree, (remotePath + "/bar/foo.json").substring(1), "{\"a\":\"d\"}");
        git.commit().setMessage("Change the file arbitrarily").call();
        final ObjectId commitId6 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(Jackson.writeValueAsString(Jackson.readTree(
                getFileContent(commitId6, remotePath + "/bar/foo.json")))).isEqualTo("{\"a\":\"d\"}");

        mirroringService.mirror().join();
        final ObjectId commitId7 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId7).isNotEqualTo(commitId6);
        content = getFileContent(commitId7, remotePath + '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("3");
        // The arbitrarily changed file is overwritten.
        assertThat(Jackson.writeValueAsString(Jackson.readTree(
                getFileContent(commitId7, remotePath + "/bar/foo.json")))).isEqualTo("{\"a\":\"c\"}");
    }

    @Nullable
    private byte[] getFileContent(ObjectId commitId, String fileName) throws IOException {
        try (ObjectReader reader = git.getRepository().newObjectReader();
             TreeWalk treeWalk = new TreeWalk(reader);
             RevWalk revWalk = new RevWalk(reader)) {
            treeWalk.addTree(revWalk.parseTree(commitId).getId());

            while (treeWalk.next()) {
                if (treeWalk.getFileMode() == FileMode.TREE) {
                    treeWalk.enterSubtree();
                    continue;
                }
                if (fileName.equals('/' + treeWalk.getPathString())) {
                    final ObjectId objectId = treeWalk.getObjectId(0);
                    return reader.open(objectId).getBytes();
                }
            }
        }
        return null;
    }

    @ParameterizedTest
    @CsvSource({
            "'', ''",
            "/local/foo, /remote",
            "/local, /remote/foo",
            "/local/foo, /remote/foo"
    })
    void LocalToRemote_gitignore(String localPath, String remotePath) throws Exception {
        pushMirrorSettings(localPath, remotePath, "\"/exclude_if_root.txt\\n**/exclude_dir\"");
        checkGitignore(localPath, remotePath);
    }

    @ParameterizedTest
    @CsvSource({
            "'', ''",
            "/local/foo, /remote",
            "/local, /remote/foo",
            "/local/foo, /remote/foo"
    })
    void localToRemote_gitignore_with_array(String localPath, String remotePath) throws Exception {
        pushMirrorSettings(localPath, remotePath, "[\"/exclude_if_root.txt\", \"exclude_dir\"]");
        checkGitignore(localPath, remotePath);
    }

    @Test
    void localToRemote_subdirectory() throws Exception {
        pushMirrorSettings("/source/main", "/target", null);

        client.forRepo(projName, REPO_FOO)
              .commit("Add a file that's not part of mirror", Change.ofTextUpsert("/not_mirrored.txt", ""))
              .push().join();

        // Mirror an empty git repository, which will;
        // - Create /target/mirror_state.json
        mirroringService.mirror().join();

        final ObjectId commitId = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        byte[] content = getFileContent(commitId, "/target/" + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        MirrorState mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("2");

        Set<String> files = listFiles(commitId);
        assertThat(files.size()).isOne(); // mirror state file.

        // Now, add some files to the git repository and mirror.
        // Note that the files not under '/source' should not be mirrored.
        client.forRepo(projName, REPO_FOO)
              .commit("Add the release dates of the 'Infamous' series",
                      Change.ofTextUpsert("/source/main/first/light.txt", "26-Aug-2014"), // mirrored
                      Change.ofJsonUpsert("/second/son.json", "{\"release\": \"21-Mar-2014\"}")) // not mirrored
              .push().join();
        mirroringService.mirror().join();

        final ObjectId commitId1 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        content = getFileContent(commitId1, "/target/" + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("3");

        files = listFiles(commitId1);
        assertThat(files.size()).isSameAs(2); // mirror state file and target/first/light.txt
        // Make sure 'target/first/light.txt' is mirrored.
        assertThat(new String(getFileContent(commitId1, "/target/first/light.txt")))
                .isEqualTo("26-Aug-2014\n");
    }

    @Test
    void localToRemote_tooManyFiles() throws Exception {
        pushMirrorSettings(null, null, null);

        // Add more than allowed number of filed.
        final ArrayList<Change<String>> changes = new ArrayList<>();
        for (int i = 0; i <= MAX_NUM_FILES; i++) {
            changes.add(Change.ofTextUpsert("/" + i + ".txt", String.valueOf(i)));
        }
        client.forRepo(projName, REPO_FOO).commit("Add a bunch of numbered files", changes).push().join();

        // Perform mirroring, which should fail.
        assertThatThrownBy(() -> mirroringService.mirror().join())
                .hasCauseInstanceOf(MirrorException.class)
                .hasMessageContaining("contains more than")
                .hasMessageContaining("file");
    }

    @Test
    void localToRemote_tooManyBytes() throws Exception {
        pushMirrorSettings(null, null, null);

        // Add files whose total size exceeds the allowed maximum.
        long remainder = MAX_NUM_BYTES + 1;
        final int defaultFileSize = (int) (MAX_NUM_BYTES / MAX_NUM_FILES * 2);
        final ArrayList<Change<String>> changes = new ArrayList<>();
        for (int i = 0;; i++) {
            final int fileSize;
            if (remainder > defaultFileSize) {
                remainder -= defaultFileSize;
                fileSize = defaultFileSize;
            } else {
                fileSize = (int) remainder;
                remainder = 0;
            }

            changes.add(Change.ofTextUpsert("/" + i + ".txt", Strings.repeat("*", fileSize)));

            if (remainder == 0) {
                break;
            }
        }
        client.forRepo(projName, REPO_FOO).commit("Add a bunch of numbered asterisks", changes).push().join();

        // Perform mirroring, which should fail.
        assertThatThrownBy(() -> mirroringService.mirror().join())
                .hasCauseInstanceOf(MirrorException.class)
                .hasMessageContaining("contains more than")
                .hasMessageContaining("byte");
    }

    @CsvSource({ "meta", "dogma" })
    @ParameterizedTest
    void cannotMirrorInternalRepositories(String localRepo) {
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
        client.forRepo(projName, Project.REPO_META)
              .commit("Add /mirrors.json",
                      Change.ofJsonUpsert("/mirrors.json",
                                          "[{" +
                                          "  \"type\": \"single\"," +
                                          "  \"direction\": \"LOCAL_TO_REMOTE\"," +
                                          "  \"localRepo\": \"" + localRepo + "\"," +
                                          (localPath != null ? "\"localPath\": \"" + localPath + "\"," : "") +
                                          "  \"remoteUri\": \"" + gitUri + firstNonNull(remotePath, "") + '"' +
                                          ",\"gitignore\": " + firstNonNull(gitignore, "\"\"") +
                                          "}]"))
              .push().join();
    }

    private Set<String> listFiles(ObjectId commitId) throws IOException {
        try (ObjectReader reader = git.getRepository().newObjectReader();
             TreeWalk treeWalk = new TreeWalk(reader);
             RevWalk revWalk = new RevWalk(reader)) {
            treeWalk.addTree(revWalk.parseTree(commitId).getId());

            final HashSet<String> files = new HashSet<>();
            while (treeWalk.next()) {
                if (treeWalk.getFileMode() == FileMode.TREE) {
                    treeWalk.enterSubtree();
                    continue;
                }
                files.add('/' + treeWalk.getPathString());
            }
            return files;
        }
    }

    private void checkGitignore(String localPath, String remotePath) throws IOException, GitAPIException {
        // Mirror an empty git repository, which will;
        // - Create /mirror_state.json
        mirroringService.mirror().join();

        // Make sure /mirror_state.json exists
        final ObjectId commitId = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        byte[] content = getFileContent(commitId, remotePath + '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        MirrorState mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("1");

        // Now, add files to the local repository and mirror.
        client.forRepo(projName, REPO_FOO)
              .commit("Add the release dates of the 'Infamous' series",
                      Change.ofTextUpsert(localPath + "/light.txt", "26-Aug-2014"),
                      Change.ofTextUpsert(localPath + "/exclude_if_root.txt", "26-Aug-2014"), // excluded
                      Change.ofTextUpsert(localPath + "/subdir/exclude_if_root.txt", "26-Aug-2014"),
                      Change.ofTextUpsert(localPath + "/subdir/exclude_dir/foo.txt", "26-Aug-2014")) // excluded
              .push().join();

        mirroringService.mirror().join();

        final ObjectId commitId1 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId1).isNotEqualTo(commitId);
        content = getFileContent(commitId1, remotePath + '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("2");
        // Remove first directory because it's localPath().
        assertThat(new String(getFileContent(commitId1, remotePath + "/light.txt"))).isEqualTo("26-Aug-2014\n");
        assertThat(new String(getFileContent(commitId1, remotePath + "/subdir/exclude_if_root.txt")))
                .isEqualTo("26-Aug-2014\n");

        // Make sure the files that match gitignore are not mirrored.
        assertThat(getFileContent(commitId1, remotePath + "/exclude_if_root.txt")).isNull();
        assertThat(getFileContent(commitId1, remotePath + "/subdir/exclude_dir/foo.txt")).isNull();
    }
}
