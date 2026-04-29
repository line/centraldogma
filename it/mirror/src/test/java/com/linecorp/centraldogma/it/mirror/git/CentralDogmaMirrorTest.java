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
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.MirrorException;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.internal.TestUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class CentralDogmaMirrorTest {

    private static final int MAX_NUM_FILES = 32;
    private static final long MAX_NUM_BYTES = 1048576; // 1 MiB

    private static final String REPO_FOO = "foo";

    // The "local" server that has the mirroring service enabled.
    @RegisterExtension
    static final CentralDogmaExtension localDogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.pluginConfigs(
                    new MirroringServicePluginConfig(true, 1, MAX_NUM_FILES, MAX_NUM_BYTES, false));
        }
    };

    // The "remote" server with auth enabled.
    @RegisterExtension
    static final CentralDogmaExtension remoteDogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.systemAdministrators(USERNAME);
        }

        @Override
        protected String accessToken() {
            final WebClient webClient = WebClient.of(
                    "http://127.0.0.1:" + remoteDogma.serverAddress().getPort());
            return TestAuthMessageUtil.getAccessToken(webClient, USERNAME, PASSWORD, true);
        }
    };

    private static CentralDogma localClient;
    private static CentralDogma remoteClient;
    private static MirroringService mirroringService;
    private static String remoteAccessToken;

    @BeforeAll
    static void init() {
        localClient = localDogma.client();
        remoteClient = remoteDogma.client();
        mirroringService = localDogma.mirroringService();

        // Obtain the access token that the remote server's client uses.
        // This is the same token created during remoteDogma.accessToken().
        final WebClient webClient = WebClient.of(
                "http://127.0.0.1:" + remoteDogma.serverAddress().getPort());
        remoteAccessToken = TestAuthMessageUtil.getAccessToken(
                webClient, USERNAME, PASSWORD, "mirrorApp", true);
    }

    private String projName;

    @BeforeEach
    void initRepos(TestInfo testInfo) {
        projName = TestUtil.normalizedDisplayName(testInfo);
        localClient.createProject(projName).join();
        localClient.createRepository(projName, REPO_FOO).join();
        remoteClient.createProject(projName).join();
        remoteClient.createRepository(projName, REPO_FOO).join();
    }

    @AfterEach
    void destroyRepos() {
        localClient.removeProject(projName).join();
        remoteClient.removeProject(projName).join();
    }

    @Test
    void localToRemote() throws Exception {
        pushMirrorSettings("/", "/");

        // Mirror an empty repository.
        mirroringService.mirror().join();

        // The remote should have .mirror_state.json.
        final Map<String, Entry<?>> remoteEntries =
                remoteClient.getFiles(projName, REPO_FOO, Revision.HEAD, PathPattern.all()).join();
        assertThat(remoteEntries).containsKey("/.mirror_state.json");

        // Mirror again with no changes - should be a no-op.
        final Revision remoteRevBefore = remoteClient.normalizeRevision(
                projName, REPO_FOO, Revision.HEAD).join();
        mirroringService.mirror().join();
        Revision remoteRevAfter = remoteClient.normalizeRevision(
                projName, REPO_FOO, Revision.HEAD).join();
        assertThat(remoteRevAfter).isEqualTo(remoteRevBefore);

        // Add files to local and mirror.
        localClient.forRepo(projName, REPO_FOO)
                   .commit("Add files",
                           Change.ofJsonUpsert("/foo.json", "{\"a\":\"b\"}"),
                           Change.ofTextUpsert("/bar.txt", "hello"))
                   .push().join();

        mirroringService.mirror().join();

        final Map<String, Entry<?>> remoteEntries2 =
                remoteClient.getFiles(projName, REPO_FOO, Revision.HEAD, PathPattern.all()).join();
        assertThat(remoteEntries2).containsKey("/foo.json");
        assertThat(remoteEntries2).containsKey("/bar.txt");
        assertThat(remoteEntries2.get("/foo.json").contentAsText()).contains("\"a\"");
        assertThat(remoteEntries2.get("/bar.txt").contentAsText()).isEqualTo("hello\n");

        remoteRevAfter = remoteClient.normalizeRevision(projName, REPO_FOO, Revision.HEAD).join();
        assertThat(remoteRevAfter).isEqualTo(remoteRevBefore.forward(1));
    }

    @Test
    void localToRemote_withPathMapping() throws Exception {
        pushMirrorSettings("/source/main", "/target");

        // Add a file under the mapped path.
        localClient.forRepo(projName, REPO_FOO)
                   .commit("Add file under source path",
                           Change.ofJsonUpsert("/source/main/config.json", "{\"key\":\"value\"}"),
                           Change.ofTextUpsert("/not_mirrored.txt", "should not appear"))
                   .push().join();

        mirroringService.mirror().join();

        final Map<String, Entry<?>> remoteEntries =
                remoteClient.getFiles(projName, REPO_FOO, Revision.HEAD, PathPattern.all()).join();
        // /target/config.json should exist (mapped from /source/main/config.json).
        assertThat(remoteEntries).containsKey("/target/config.json");
        // /not_mirrored.txt should NOT exist on remote (outside mirror path).
        assertThat(remoteEntries).doesNotContainKey("/not_mirrored.txt");
    }

    @Test
    void localToRemote_removal() throws Exception {
        pushMirrorSettings("/", "/");

        // Add a file and mirror.
        localClient.forRepo(projName, REPO_FOO)
                   .commit("Add file", Change.ofTextUpsert("/to_remove.txt", "content"))
                   .push().join();

        mirroringService.mirror().join();

        Map<String, Entry<?>> remoteEntries =
                remoteClient.getFiles(projName, REPO_FOO, Revision.HEAD, PathPattern.all()).join();
        assertThat(remoteEntries).containsKey("/to_remove.txt");

        // Remove the file locally and mirror again.
        localClient.forRepo(projName, REPO_FOO)
                   .commit("Remove file", Change.ofRemoval("/to_remove.txt"))
                   .push().join();

        mirroringService.mirror().join();

        remoteEntries = remoteClient.getFiles(projName, REPO_FOO, Revision.HEAD, PathPattern.all()).join();
        assertThat(remoteEntries).doesNotContainKey("/to_remove.txt");
    }

    @Test
    void remoteToLocal() throws Exception {
        pushMirrorSettings("/", "/", MirrorDirection.REMOTE_TO_LOCAL);

        // Add files to remote.
        remoteClient.forRepo(projName, REPO_FOO)
                    .commit("Add files",
                            Change.ofJsonUpsert("/remote_file.json", "{\"remote\":true}"),
                            Change.ofTextUpsert("/remote_text.txt", "from remote"))
                    .push().join();

        mirroringService.mirror().join();

        // Verify local has the files.
        final Map<String, Entry<?>> localEntries =
                localClient.getFiles(projName, REPO_FOO, Revision.HEAD, PathPattern.all()).join();
        assertThat(localEntries).containsKey("/remote_file.json");
        assertThat(localEntries).containsKey("/remote_text.txt");
        assertThat(localEntries).containsKey("/mirror_state.json");
        assertThat(localEntries.get("/remote_text.txt").contentAsText()).isEqualTo("from remote\n");

        // Mirror again with no changes - should be a no-op.
        final Revision localRevBefore = localClient.normalizeRevision(
                projName, REPO_FOO, Revision.HEAD).join();
        mirroringService.mirror().join();
        final Revision localRevAfter = localClient.normalizeRevision(
                projName, REPO_FOO, Revision.HEAD).join();
        assertThat(localRevAfter).isEqualTo(localRevBefore);
    }

    @Test
    void remoteToLocal_removal() throws Exception {
        pushMirrorSettings("/", "/", MirrorDirection.REMOTE_TO_LOCAL);

        // Add a file to remote and mirror.
        remoteClient.forRepo(projName, REPO_FOO)
                    .commit("Add file", Change.ofTextUpsert("/to_remove.txt", "content"))
                    .push().join();

        mirroringService.mirror().join();

        Map<String, Entry<?>> localEntries =
                localClient.getFiles(projName, REPO_FOO, Revision.HEAD, PathPattern.all()).join();
        assertThat(localEntries).containsKey("/to_remove.txt");

        // Remove the file from remote and mirror again.
        remoteClient.forRepo(projName, REPO_FOO)
                    .commit("Remove file", Change.ofRemoval("/to_remove.txt"))
                    .push().join();

        mirroringService.mirror().join();

        localEntries = localClient.getFiles(projName, REPO_FOO, Revision.HEAD, PathPattern.all()).join();
        assertThat(localEntries).doesNotContainKey("/to_remove.txt");
    }

    @Test
    void localToRemote_tooManyFiles() throws Exception {
        pushMirrorSettings("/", "/");

        for (int i = 0; i <= MAX_NUM_FILES; i++) {
            localClient.forRepo(projName, REPO_FOO)
                       .commit("Add file " + i, Change.ofTextUpsert("/" + i + ".txt", String.valueOf(i)))
                       .push().join();
        }

        assertThatThrownBy(() -> mirroringService.mirror().join())
                .hasCauseInstanceOf(MirrorException.class)
                .hasMessageContaining("contains more than")
                .hasMessageContaining("file");
    }

    private void pushMirrorSettings(String localPath, String remotePath) {
        pushMirrorSettings(localPath, remotePath, MirrorDirection.LOCAL_TO_REMOTE);
    }

    private void pushMirrorSettings(String localPath, String remotePath, MirrorDirection direction) {
        final InetSocketAddress remoteAddr = remoteDogma.serverAddress();
        final String remoteUri = "dogma://" + remoteAddr.getHostString() + ':' + remoteAddr.getPort() +
                                 '/' + projName + '/' + REPO_FOO + ".dogma" + remotePath;

        final String credId = "access-token";
        final String credName = credentialName(projName, credId);
        try {
            localClient.forRepo(projName, Project.REPO_DOGMA)
                       .commit("Add credential",
                               Change.ofJsonUpsert(credentialFile(credName),
                                                   "{ \"type\": \"ACCESS_TOKEN\"," +
                                                   "  \"name\": \"" + credName + "\"," +
                                                   "  \"accessToken\": \"" + remoteAccessToken + "\" }"))
                       .push().join();
        } catch (CompletionException e) {
            if (!(e.getCause() instanceof RedundantChangeException)) {
                throw e;
            }
        }

        localClient.forRepo(projName, Project.REPO_DOGMA)
                   .commit("Add mirror config",
                           Change.ofJsonUpsert(
                                   "/repos/" + REPO_FOO + "/mirrors/foo.json",
                                   '{' +
                                   "  \"id\": \"foo\"," +
                                   "  \"enabled\": true," +
                                   "  \"direction\": \"" + direction + "\"," +
                                   "  \"localRepo\": \"" + REPO_FOO + "\"," +
                                   "  \"localPath\": \"" + localPath + "\"," +
                                   "  \"remoteUri\": \"" + remoteUri + "\"," +
                                   "  \"schedule\": \"0 0 0 1 1 ? 2099\"," +
                                   "  \"credentialName\": \"" + credName + '"' +
                                   '}'))
                   .push().join();
    }
}
