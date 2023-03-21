/*
 * Copyright 2023 LINE Corporation
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

import static com.linecorp.centraldogma.it.mirror.git.GitTestUtil.getFileContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.eclipse.jgit.lib.Constants.R_HEADS;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sshd.server.SshServer;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.internal.mirror.MirrorState;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.internal.TestUtil;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class ForceRefUpdateTest {

    private static final String REPO_FOO = "foo";
    private static final String LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME = ".mirror_state.json";

    @RegisterExtension
    static TemporaryGitRepoExtension git = new TemporaryGitRepoExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.mirroringEnabled(true);
        }
    };

    private static MirroringService mirroringService;
    private String projName;
    private KeyPair keyPair;
    private String privateKey;
    private String publicKey;

    @BeforeAll
    static void init() {
        mirroringService = dogma.mirroringService();
    }

    @BeforeEach
    void initDogmaRepo(TestInfo testInfo) throws NoSuchAlgorithmException {
        projName = TestUtil.normalizedDisplayName(testInfo);
        dogma.client().createProject(projName).join();
        dogma.client().createRepository(projName, REPO_FOO).join();

        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        keyPair = kpg.generateKeyPair();
    }

    @AfterEach
    void afterEach() {
        dogma.client()
             .forRepo(projName, Project.REPO_META)
             .commit("cleanup",
                     Change.ofRemoval("/credentials.json"),
                     Change.ofRemoval("/mirrors.json"))
             .push().join();
    }

    @Test
    @Disabled("Disable for test")
    void testLocalToRemote() throws Exception {
        final AtomicBoolean throttleGitPush = new AtomicBoolean();

        try (SshServer sshd = SshServer.setUpDefaultServer()) {
            final String gitUri = "git+ssh://127.0.0.1:" + sshd.getPort() + "/.git/";
            pushMirror(gitUri, MirrorDirection.LOCAL_TO_REMOTE);
            pushCredentials(publicKey, privateKey);

            // 1. Perform the initial mirroring.
            mirroringService.mirror().join();
            assertRevisionAndContent("1", null);

            // 2. Create a new commit.
            dogma.client().forRepo(projName, REPO_FOO)
                 .commit("Add a commit", Change.ofJsonUpsert("/foo.json", "{\"a\":\"b\"}"))
                 .push().join();
            mirroringService.mirror().join();
            assertRevisionAndContent("2", "{\"a\":\"b\"}");

            // 3. Turn on throttling so that local is ahead of remote.
            throttleGitPush.set(true);
            dogma.client().forRepo(projName, REPO_FOO)
                 .commit("Add a commit", Change.ofJsonUpsert("/foo.json", "{\"a\":\"c\"}"))
                 .push().join();
            assertThatThrownBy(() -> mirroringService.mirror().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(MirrorException.class);
            assertRevisionAndContent("2", "{\"a\":\"b\"}");

            // 4. Turn off throttling, ref update should succeed.
            throttleGitPush.set(false);
            mirroringService.mirror().join();
            assertRevisionAndContent("3", "{\"a\":\"c\"}");

            // 5. Turn on throttling again so that local is ahead of remote.
            throttleGitPush.set(true);
            dogma.client().forRepo(projName, REPO_FOO)
                 .commit("Add a commit", Change.ofJsonUpsert("/foo.json", "{\"a\":\"d\"}"))
                 .push().join();
            assertThatThrownBy(() -> mirroringService.mirror().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(MirrorException.class);
            assertRevisionAndContent("3", "{\"a\":\"c\"}");

            // 6. Push another commit to make commits diverge.
            dogma.client().forRepo(projName, REPO_FOO)
                 .commit("Add a commit", Change.ofJsonUpsert("/foo.json", "{\"a\":\"e\"}"))
                 .push().join();

            // 7. Turn off throttling, ref update should succeed.
            throttleGitPush.set(false);
            mirroringService.mirror().join();
            assertRevisionAndContent("5", "{\"a\":\"e\"}");
        }
    }

    private static void assertRevisionAndContent(String expectedRevision,
                                                 @Nullable String expectedContent) throws Exception {
        final ObjectId commitId = git.git().getRepository().exactRef(R_HEADS + "master").getObjectId();
        final byte[] content =
                getFileContent(git.git(), commitId, '/' + LOCAL_TO_REMOTE_MIRROR_STATE_FILE_NAME);
        final MirrorState mirrorState = Jackson.readValue(content, MirrorState.class);
        assertThat(mirrorState.sourceRevision()).isEqualTo(expectedRevision);
        final byte[] fileContent = getFileContent(git.git(), commitId, "/foo.json");
        if (fileContent == null) {
            assertThat(expectedContent).isNull();
        } else {
            assertThat(Jackson.writeValueAsString(Jackson.readTree(fileContent))).isEqualTo(expectedContent);
        }
    }

    private void pushCredentials(String pubKey, String privKey) {
        dogma.client().forRepo(projName, Project.REPO_META)
             .commit("Add a mirror",
                     Change.ofJsonUpsert("/credentials.json",
                                         "[{" +
                                         "  \"type\": \"public_key\"," +
                                         "  \"hostnamePatterns\": [ \"^.*$\" ]," +
                                         "  \"username\": \"" + "git" + "\"," +
                                         "  \"publicKey\": \"" + pubKey + "\"," +
                                         "  \"privateKey\": \"" + privKey + '"' +
                                         "}]")
             ).push().join();
    }

    private void pushMirror(String gitUri, MirrorDirection mirrorDirection) {
        dogma.client().forRepo(projName, Project.REPO_META)
             .commit("Add a mirror",
                     Change.ofJsonUpsert("/mirrors.json",
                                         "[{" +
                                         "  \"type\": \"single\"," +
                                         "  \"direction\": \"" + mirrorDirection.name()  + "\"," +
                                         "  \"localRepo\": \"" + REPO_FOO + "\"," +
                                         "  \"localPath\": \"/\"," +
                                         "  \"remoteUri\": \"" + gitUri + "\"," +
                                         "  \"schedule\": \"0 0 0 1 1 ? 2099\"" +
                                         "}]"))
             .push().join();
    }
}
