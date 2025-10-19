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

package com.linecorp.centraldogma.it.mirror.git;

import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static com.linecorp.centraldogma.it.mirror.git.MirrorRunnerTest.PRIVATE_KEY_FILE;
import static com.linecorp.centraldogma.it.mirror.git.MirrorRunnerTest.TEST_MIRROR_ID;
import static com.linecorp.centraldogma.it.mirror.git.MirrorRunnerTest.getCreateCredentialRequest;
import static com.linecorp.centraldogma.it.mirror.git.TestMirrorRunnerListener.creationCount;
import static com.linecorp.centraldogma.it.mirror.git.TestMirrorRunnerListener.startCount;
import static com.linecorp.centraldogma.it.mirror.git.TestMirrorRunnerListener.updateCount;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.jspecify.annotations.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.internal.api.v1.MirrorRequest;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.credential.CreateCredentialRequest;
import com.linecorp.centraldogma.server.internal.api.sysadmin.MirrorAccessControlRequest;
import com.linecorp.centraldogma.server.internal.mirror.MirrorAccessControl;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class MirrorAccessControlTest {

    static final String TEST_PROJ = "test_mirror_access_control";
    static final String TEST_REPO = "bar";

    @RegisterExtension
    final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Nullable
        private String accessToken;

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.systemAdministrators(USERNAME);
            builder.pluginConfigs(new MirroringServicePluginConfig(true));
        }

        @Override
        protected String accessToken() {
            return getAccessToken0();
        }

        private String getAccessToken0() {
            if (accessToken != null) {
                return accessToken;
            }
            accessToken = getAccessToken(
                    WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort()),
                    USERNAME, PASSWORD, true);
            return accessToken;
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject(TEST_PROJ).join();
            client.createRepository(TEST_PROJ, TEST_REPO).join();
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    private BlockingWebClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = dogma.blockingHttpClient();
        TestMirrorRunnerListener.reset();
    }

    @Test
    void shouldControlMirroringWithAccessController() throws Exception {
        ResponseEntity<MirrorAccessControl> accessResponse =
                client.prepare()
                      .post("/api/v1/mirror/access")
                      .contentJson(new MirrorAccessControlRequest(
                              "default",
                              ".*",
                              false,
                              "disallow by default",
                              Integer.MAX_VALUE))
                      .asJson(MirrorAccessControl.class)
                      .execute();
        assertThat(accessResponse.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(accessResponse.content().id()).isEqualTo("default");

        createMirror();
        final String listenerKey = TEST_PROJ + '/' + TEST_MIRROR_ID + '/' + Author.SYSTEM.name();
        await().untilAsserted(() -> {
            assertThat(creationCount.get("git+ssh://github.com/line/centraldogma-authtest.git"))
                    .isOne();

            assertThat(startCount.get(listenerKey)).isNull();
        });

        accessResponse = client.prepare()
                               .post("/api/v1/mirror/access")
                               .contentJson(new MirrorAccessControlRequest(
                                       "centraldogma-authtest",
                                       ".*github.com/line/centraldogma-authtest.git$",
                                       true,
                                       "allow centraldogma-authtest",
                                       0))
                               .asJson(MirrorAccessControl.class)
                               .execute();
        assertThat(accessResponse.status()).isEqualTo(HttpStatus.CREATED);

        await().untilAsserted(() -> {
            assertThat(startCount).hasSizeGreaterThan(0);
            final Integer numMirroring = startCount.get(listenerKey);
            assertThat(numMirroring).isNotNull();
            assertThat(numMirroring).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    void testMirrorCreationEvent() throws Exception {
        assertThat(creationCount.get("git+ssh://github.com/line/centraldogma-authtest.git"))
                .isNull();
        assertThat(updateCount.get("git+ssh://github.com/line/centraldogma-authtest.git"))
                .isNull();
        createMirror();
        await().untilAsserted(() -> {
            assertThat(creationCount.get("git+ssh://github.com/line/centraldogma-authtest.git"))
                    .isOne();
        });

        final MirrorRequest updating = newMirror("/foo/");

        final ResponseEntity<PushResultDto> response =
                client.prepare()
                      .put("/api/v1/projects/{proj}/repos/{repo}/mirrors/{mirrorId}")
                      .pathParam("proj", TEST_PROJ)
                      .pathParam("repo", TEST_REPO)
                      .pathParam("mirrorId", TEST_MIRROR_ID)
                      .contentJson(updating)
                      .asJson(PushResultDto.class)
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        await().untilAsserted(() -> {
            assertThat(updateCount.get("git+ssh://github.com/line/centraldogma-authtest.git"))
                    .isOne();
        });
    }

    private void createMirror() throws Exception {
        final CreateCredentialRequest credential = getCreateCredentialRequest(TEST_PROJ, TEST_REPO);
        ResponseEntity<PushResultDto> response =
                client.prepare()
                      .post("/api/v1/projects/{proj}/repos/{repo}/credentials")
                      .pathParam("proj", TEST_PROJ)
                      .pathParam("repo", TEST_REPO)
                      .contentJson(credential)
                      .asJson(PushResultDto.class)
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);

        final MirrorRequest newMirror = newMirror("/");
        response = client.prepare()
                         .post("/api/v1/projects/{proj}/repos/{repo}/mirrors")
                         .pathParam("proj", TEST_PROJ)
                         .pathParam("repo", TEST_REPO)
                         .contentJson(newMirror)
                         .asJson(PushResultDto.class)
                         .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
    }

    private static MirrorRequest newMirror(String localPath) {
        return new MirrorRequest(TEST_MIRROR_ID,
                                 true,
                                 TEST_PROJ,
                                 "0/1 * * * * ?",
                                 "REMOTE_TO_LOCAL",
                                 TEST_REPO,
                                 localPath,
                                 "git+ssh",
                                 "github.com/line/centraldogma-authtest.git",
                                 "/",
                                 "main",
                                 null,
                                 credentialName(TEST_PROJ, TEST_REPO, PRIVATE_KEY_FILE),
                                 null);
    }
}
