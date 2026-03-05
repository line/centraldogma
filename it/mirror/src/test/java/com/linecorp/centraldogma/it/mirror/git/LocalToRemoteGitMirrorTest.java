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
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialFile;
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static com.linecorp.centraldogma.it.mirror.git.GitMirrorIntegrationTest.addToGitIndex;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.eclipse.jgit.lib.Constants.R_HEADS;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.base.Strings;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.MirrorException;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.internal.mirror.MirrorState;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.internal.TestUtil;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class LocalToRemoteGitMirrorTest {

    private static final String LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME = ".mirror_state.json";

    private static final int MAX_NUM_FILES = 32;
    private static final long MAX_NUM_BYTES = 1048576; // 1 MiB

    private static final String REPO_FOO = "foo";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.pluginConfigs(
                    new MirroringServicePluginConfig(true, 1, MAX_NUM_FILES, MAX_NUM_BYTES, false));
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
    static final TemporaryGitRepoExtension gitExtension = new TemporaryGitRepoExtension() {
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
    void initGitRepo() throws Exception {
        gitWorkTree = gitExtension.gitWorkTree();
        git = gitExtension.git();
        gitUri = gitExtension.fileUri();
    }

    @BeforeEach
    void initDogmaRepo(TestInfo testInfo) {
        projName = TestUtil.normalizedDisplayName(testInfo);
        client.createProject(projName).join();
        client.createRepository(projName, REPO_FOO).join();
    }

    @AfterEach
    void destroyDogmaRepo() throws IOException {
        client.removeProject(projName).join();
    }

    @ParameterizedTest
    @CsvSource({
            "'', ''",
            "/local/foo, /remote",
            "/local, /remote/bar",
            "/local/foo, /remote/bar"
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

        //language=JSON5
        final String json5 = "{\n" +
                             "  // This is a single-line comment\n" +
                             "  \"key\": \"value\"\n" +
                             '}';
        //language=yaml
        final String yaml = "# This is a comment\n" +
                            "YAML: true";

        // Create a new commit
        client.forRepo(projName, REPO_FOO)
              .commit("Add a commit",
                      Change.ofJsonUpsert(localPath + "/foo.json", "{\"a\":\"b\"}"),
                      Change.ofJsonUpsert(localPath + "/bar/foo.json", "{\"a\":\"c\"}"),
                      Change.ofJsonUpsert(localPath + "/bar/foo.json5", json5),
                      Change.ofYamlUpsert(localPath + "/bar/foo.yaml", yaml),
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
        final String fooJson5 = new String(getFileContent(commitId3, remotePath + "/bar/foo.json5"));
        // Make sure the JSON5 content is mirrored as-is.
        assertThat(fooJson5).isEqualTo(json5 + '\n');
        final String fooYaml = new String(getFileContent(commitId3, remotePath + "/bar/foo.yaml"));
        assertThat(fooYaml).isEqualTo(yaml + '\n');

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

    @ParameterizedTest
    @CsvSource({
            "'', ''",
            "/local/foo, /remote",
            "/local, /remote/bar",
            "/local/foo, /remote/bar"
    })
    void localToRemote_gitignore(String localPath, String remotePath) throws Exception {
        pushMirrorSettings(localPath, remotePath, "\"/exclude_if_root.txt\\n**/exclude_dir\"");
        checkGitignore(localPath, remotePath);
    }

    @ParameterizedTest
    @CsvSource({
            "'', ''",
            "/local/foo, /remote",
            "/local, /remote/bar",
            "/local/foo, /remote/bar"
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
        for (int i = 0; ; i++) {
            final int fileSize;
            if (remainder > defaultFileSize) {
                remainder -= defaultFileSize;
                fileSize = defaultFileSize;
            } else {
                fileSize = (int) remainder;
                remainder = 0;
            }

            final Change<String> change = Change.ofTextUpsert("/" + i + ".txt",
                                                              Strings.repeat("*", fileSize));

            client.forRepo(projName, REPO_FOO)
                  .commit("Add a bunch of numbered asterisks" + " i", change)
                  .push()
                  .join();
            if (remainder == 0) {
                break;
            }
        }

        // Perform mirroring, which should fail.
        assertThatThrownBy(() -> mirroringService.mirror().join())
                .hasCauseInstanceOf(MirrorException.class)
                .hasMessageContaining("contains more than")
                .hasMessageContaining("byte");
    }

    @Test
    void localToRemote_multipleMirrorsOnDifferentPaths() throws Exception {
        // Add files to different local paths
        client.forRepo(projName, REPO_FOO)
              .commit("Add files to multiple paths",
                      Change.ofJsonUpsert("/source1/file1.json", "{\"key\":\"value1\"}"),
                      Change.ofJsonUpsert("/source2/file2.json", "{\"key\":\"value2\"}"))
              .push().join();

        // Set up first mirror: local /source1 -> remote /target1
        pushMirrorSettings("mirror1", "/source1", "/target1", null);
        // Set up second mirror: local /source2 -> remote /target2
        pushMirrorSettings("mirror2", "/source2", "/target2", null);

        mirroringService.mirror().join();

        final ObjectId commitId1 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();

        // Verify both mirrors work correctly
        // Check /target1 has file1.json
        byte[] content1 = getFileContent(commitId1, "/target1/file1.json");
        assertThat(new String(content1)).isEqualTo("{\"key\":\"value1\"}\n");

        // Check /target2 has file2.json
        byte[] content2 = getFileContent(commitId1, "/target2/file2.json");
        assertThat(new String(content2)).isEqualTo("{\"key\":\"value2\"}\n");

        // Verify mirror state files exist for both paths
        assertThat(getFileContent(commitId1, "/target1/" + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME)).isNotNull();
        assertThat(getFileContent(commitId1, "/target2/" + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME)).isNotNull();

        // Mirror again without any changes - should not create a new commit
        mirroringService.mirror().join();
        final ObjectId commitId2 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId2).isEqualTo(commitId1);

        // Update file in source1
        client.forRepo(projName, REPO_FOO)
              .commit("Update file1.json",
                      Change.ofJsonUpsert("/source1/file1.json", "{\"key\":\"updated-value1\"}"))
              .push().join();

        mirroringService.mirror().join();
        final ObjectId commitId3 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId3).isNotEqualTo(commitId2);

        // Verify only target1 is updated
        content1 = getFileContent(commitId3, "/target1/file1.json");
        assertThatJson(new String(content1)).isEqualTo("{\"key\":\"updated-value1\"}");
        // target2 should remain unchanged
        content2 = getFileContent(commitId3, "/target2/file2.json");
        assertThatJson(new String(content2)).isEqualTo("{\"key\":\"value2\"}");

        // Update file in source2
        client.forRepo(projName, REPO_FOO)
              .commit("Update file2.json",
                      Change.ofJsonUpsert("/source2/file2.json", "{\"key\":\"updated-value2\"}"))
              .push().join();

        mirroringService.mirror().join();
        final ObjectId commitId4 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId4).isNotEqualTo(commitId3);

        // Verify target2 is now updated as well
        content2 = getFileContent(commitId4, "/target2/file2.json");
        assertThatJson(new String(content2)).isEqualTo("{\"key\":\"updated-value2\"}");

        addToGitIndex(git, gitWorkTree, "target1/file1.json", "{\"key\":\"updated-value3\"}");
        final ObjectId commitId5 = git.commit().setMessage("Change file1.json").call().toObjectId();
        content1 = getFileContent(commitId5, "/target1/file1.json");
        assertThatJson(new String(content1)).isEqualTo("{\"key\":\"updated-value3\"}");

        mirroringService.mirror().join();
        final ObjectId commitId6 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId6).isNotEqualTo(commitId5);
        content2 = getFileContent(commitId6, "/target1/file1.json");
        assertThatJson(new String(content2)).isEqualTo("{\"key\":\"updated-value1\"}");
        content2 = getFileContent(commitId6, "/target2/file2.json");
        assertThatJson(new String(content2)).isEqualTo("{\"key\":\"updated-value2\"}");
    }

    @CsvSource({ "meta", "dogma" })
    @ParameterizedTest
    void cannotMirrorInternalRepositories(String localRepo) {
        assertThatThrownBy(() -> pushMirrorSettings(localRepo, "/", "/", null, MirrorDirection.LOCAL_TO_REMOTE))
                .hasCauseInstanceOf(CentralDogmaException.class)
                .hasMessageContaining("invalid localRepo:");
    }

    private void pushMirrorSettings(@Nullable String localPath, @Nullable String remotePath,
                                    @Nullable String gitignore) {
        pushMirrorSettings("foo", REPO_FOO, localPath, remotePath, gitignore, MirrorDirection.LOCAL_TO_REMOTE);
    }

    private void pushMirrorSettings(String mirrorId, @Nullable String localPath, @Nullable String remotePath,
                                    @Nullable String gitignore) {
        pushMirrorSettings(mirrorId, REPO_FOO, localPath, remotePath, gitignore,
                           MirrorDirection.LOCAL_TO_REMOTE);
    }

    private void pushMirrorSettings(String localRepo, @Nullable String localPath, @Nullable String remotePath,
                                    @Nullable String gitignore, MirrorDirection direction) {
        pushMirrorSettings("foo", localRepo, localPath, remotePath, gitignore, direction);
    }

    private void pushMirrorSettings(String mirrorId, String localRepo, @Nullable String localPath,
                                    @Nullable String remotePath, @Nullable String gitignore,
                                    MirrorDirection direction) {
        final String localPath0 = localPath == null ? "/" : localPath;
        final String remoteUri = gitUri + firstNonNull(remotePath, "");
        try {
            final String credentialName = credentialName(projName, "none");
            client.forRepo(projName, Project.REPO_DOGMA)
                  .commit("Add /credentials/none",
                          Change.ofJsonUpsert(credentialFile(credentialName),
                                              "{ " +
                                              "\"type\": \"NONE\"," +
                                              "\"name\": \"" + credentialName + '"' +
                                              '}'))
                  .push().join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RedundantChangeException) {
                // The same content can be pushed several times.
            } else {
                throw e;
            }
        }
        client.forRepo(projName, Project.REPO_DOGMA)
              .commit("Add /repos/" + localRepo + "/mirrors/" + mirrorId + ".json",
                      Change.ofJsonUpsert("/repos/" + localRepo + "/mirrors/" + mirrorId + ".json",
                                          '{' +
                                          " \"id\": \"" + mirrorId + "\"," +
                                          " \"enabled\": true," +
                                          "  \"type\": \"single\"," +
                                          "  \"direction\": \"" + direction + "\"," +
                                          "  \"localRepo\": \"" + localRepo + "\"," +
                                          "  \"localPath\": \"" + localPath0 + "\"," +
                                          "  \"remoteUri\": \"" + remoteUri + "\"," +
                                          "  \"schedule\": \"0 0 0 1 1 ? 2099\"," +
                                          "  \"gitignore\": " + firstNonNull(gitignore, "\"\"") + ',' +
                                          "  \"credentialName\": \"" +
                                          credentialName(projName, "none") + '"' +
                                          '}'))
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

    @Test
    void changeDirection() throws Exception {
        pushMirrorSettings(null, null, null);

        // Mirror an empty Central Dogma repository, which will;
        // - Create /.mirror_state.json
        mirroringService.mirror().join();

        final ObjectId commitId1 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        byte[] content = getFileContent(commitId1, '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        MirrorState mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("1");

        // Create a new commit
        client.forRepo(projName, REPO_FOO)
              .commit("Add a commit",
                      Change.ofJsonUpsert("/foo.json", "{\"a\":\"b\"}"),
                      Change.ofJsonUpsert("/bar/foo.json", "{\"a\":\"c\"}"),
                      Change.ofTextUpsert("/baz/foo.txt", "\"a\": \"b\"\n"))
              .push().join();

        mirroringService.mirror().join();

        final ObjectId commitId2 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        content = getFileContent(commitId2, '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("2");
        assertThat(Jackson.writeValueAsString(Jackson.readTree(
                getFileContent(commitId2, "/foo.json")))).isEqualTo("{\"a\":\"b\"}");
        assertThat(Jackson.writeValueAsString(Jackson.readTree(
                getFileContent(commitId2, "/bar/foo.json")))).isEqualTo("{\"a\":\"c\"}");
        assertThat(new String(getFileContent(commitId2, "/baz/foo.txt")))
                .isEqualTo("\"a\": \"b\"\n");

        // Change the direction
        pushMirrorSettings(REPO_FOO, null, null, null, MirrorDirection.REMOTE_TO_LOCAL);
        addToGitIndex(git, gitWorkTree, "foo.json", "{\"a\":\"foo\"}");
        git.commit().setMessage("Modify foo.json").call();
        mirroringService.mirror().join();

        final Map<String, Entry<?>> entries = client.forRepo(projName, REPO_FOO)
                                                    .file(PathPattern.all())
                                                    .get()
                                                    .join();
        assertThat(entries.size()).isEqualTo(2);
        assertThat(entries.get("/foo.json")).isEqualTo(
                Entry.ofJson(new Revision(3), "/foo.json", "{\"a\":\"foo\"}"));

        assertThat(entries.get("/mirror_state.json")).isNotNull();
    }

    @Test
    void localToRemote_localPathChange_triggersMirror() throws Exception {
        pushMirrorSettings("/source1", null, null);

        final ObjectId commitId0 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        client.forRepo(projName, REPO_FOO)
              .commit("Add files",
                      Change.ofJsonUpsert("/source1/foo.json", "{\"a\":\"b\"}"),
                      Change.ofJsonUpsert("/source2/bar.json", "{\"c\":\"d\"}"))
              .push().join();

        // Perform initial mirroring
        mirroringService.mirror().join();
        final ObjectId commitId1 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        byte[] content = getFileContent(commitId1, '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        MirrorState mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("2");
        assertThat(mirrorState.remoteRevision()).isEqualTo(commitId0.name());
        assertThat(mirrorState.localRevision()).isEqualTo("2");
        assertThat(mirrorState.configHash()).isNotEmpty();

        assertThat(new String(getFileContent(commitId1, "/foo.json"), StandardCharsets.UTF_8))
                .isEqualTo("{\"a\":\"b\"}\n");
        assertThat(getFileContent(commitId1, "/bar.json")).isNull();

        // Mirror again without any changes - should not create a new commit
        mirroringService.mirror().join();
        final ObjectId commitId2 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId2).isEqualTo(commitId1);

        // Now change the local path configuration to /source2
        pushMirrorSettings("/source2", null, null);

        // Mirror again - should run because local path changed
        mirroringService.mirror().join();
        final ObjectId commitId3 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId3).isNotEqualTo(commitId2);

        content = getFileContent(commitId3, '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("2");
        assertThat(new String(getFileContent(commitId3, "/bar.json"), StandardCharsets.UTF_8))
                .isEqualTo("{\"c\":\"d\"}\n");
        assertThat(getFileContent(commitId3, "/foo.json")).isNull();

        // Mirror again without any changes - should not create a new commit
        mirroringService.mirror().join();
        final ObjectId commitId4 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId4).isEqualTo(commitId3);
    }

    @Test
    void localToRemote_remotePathChange_triggersMirror() throws Exception {
        client.forRepo(projName, REPO_FOO)
              .commit("Add a file",
                      Change.ofJsonUpsert("/foo.json", "{\"a\":\"b\"}"))
              .push().join();

        pushMirrorSettings("", "/remote1", null);

        // Perform initial mirroring
        mirroringService.mirror().join();
        final ObjectId commitId1 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        byte[] content = getFileContent(commitId1, "/remote1/" + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        MirrorState mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("2");

        // Verify the file is mirrored to /remote1
        assertThat(getFileContent(commitId1, "/remote1/foo.json")).isNotNull();

        // Mirror again without any changes - should not create a new commit
        mirroringService.mirror().join();
        final ObjectId commitId2 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId2).isEqualTo(commitId1);

        // Now change the remote path configuration to /remote2
        pushMirrorSettings("", "/remote2", null);

        // Mirror again - should run because remote path changed
        mirroringService.mirror().join();
        final ObjectId commitId3 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId3).isNotEqualTo(commitId2);

        // Verify the file is now mirrored to /remote2
        content = getFileContent(commitId3, "/remote2/" + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo("2");
        assertThat(getFileContent(commitId3, "/remote2/foo.json")).isNotNull();
    }

    @Test
    void localToRemote_remoteChangesOverwrittenByLocal() throws Exception {
        final ObjectId commitId0 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        client.forRepo(projName, REPO_FOO)
              .commit("Add a file",
                      Change.ofJsonUpsert("/foo.json", "{\"a\":\"b\"}"))
              .push().join();

        pushMirrorSettings(null, null, null);

        // Perform initial mirroring
        mirroringService.mirror().join();
        final ObjectId commitId1 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        byte[] content = getFileContent(commitId1, '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        MirrorState mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.previousTargetRevision()).isEqualTo("2");
        assertThat(mirrorState.localRevision()).isEqualTo("2");
        assertThat(mirrorState.remoteRevision()).isEqualTo(commitId0.name());
        assertThat(mirrorState.configHash()).isNotEmpty();

        // Verify the file is mirrored
        assertThat(new String(getFileContent(commitId1, "/foo.json")))
                .isEqualTo("{\"a\":\"b\"}\n");
        mirroringService.mirror().join();

        // Modify the file directly in the remote Git repository (without mirroring)
        addToGitIndex(git, gitWorkTree, "foo.json", "{\"a\":\"modified\"}");
        addToGitIndex(git, gitWorkTree, ".mirror_state.json", new String(content));
        git.commit().setMessage("Modify foo.json directly in remote").call();
        final ObjectId commitId2 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId2).isNotEqualTo(commitId1);

        assertThat(new String(getFileContent(commitId2, "/foo.json")))
                .isEqualTo("{\"a\":\"modified\"}");

        // Mirror again without any local changes - remote changes should be overwritten
        mirroringService.mirror().join();
        final ObjectId commitId3 = git.getRepository().exactRef(R_HEADS + "master").getObjectId();
        assertThat(commitId3).isNotEqualTo(commitId2);

        // Verify the remote modification is overwritten by the local content
        content = getFileContent(commitId3, '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.previousTargetRevision()).isEqualTo("2");
        assertThat(mirrorState.localRevision()).isEqualTo("2");
        assertThat(mirrorState.remoteRevision()).isEqualTo(commitId2.name());
        assertThat(mirrorState.configHash()).isNotEmpty();
        assertThat(new String(getFileContent(commitId3, "/foo.json")))
                .isEqualTo("{\"a\":\"b\"}\n");
    }

    byte[] getFileContent(ObjectId commitId, String fileName) throws IOException {
        return GitTestUtil.getFileContent(git, commitId, fileName);
    }
}
