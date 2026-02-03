/*
 * Copyright 2024 LINE Corporation
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
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.io.Resources;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.internal.api.v1.MirrorRequest;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.credential.CreateCredentialRequest;
import com.linecorp.centraldogma.server.internal.api.sysadmin.MirrorAccessControlRequest;
import com.linecorp.centraldogma.server.internal.credential.SshKeyCredential;
import com.linecorp.centraldogma.server.internal.mirror.MirrorAccessControl;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirrorStatus;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class MirrorRunnerTest {

    static final String FOO_PROJ = "foo";
    static final String BAR_REPO = "bar";
    static final String PRIVATE_KEY_FILE = "ecdsa_256.openssh";
    static final String TEST_MIRROR_ID = "test-mirror";

    // The SSH test key file is stored as GitHub Secrets and automatically created during CI builds.

    @BeforeAll
    static void checkSshKeyFileExists() {
        Assumptions.assumeTrue(
                MirrorRunnerTest.class.getResource(PRIVATE_KEY_FILE) != null,
                "Skipping test because SSH key file '" + PRIVATE_KEY_FILE + "' does not exist. ");
    }

    @RegisterExtension
    CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.systemAdministrators(USERNAME);
        }

        @Override
        protected String accessToken() {
            return getAccessToken0();
        }

        private String getAccessToken0() {
            return getAccessToken(
                    WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort()),
                    USERNAME, PASSWORD, true);
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject(FOO_PROJ).join();
            client.createRepository(FOO_PROJ, BAR_REPO).join();
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    private BlockingWebClient systemAdminClient;

    @BeforeEach
    void setUp() throws Exception {
        systemAdminClient = dogma.blockingHttpClient();
        TestMirrorRunnerListener.reset();
    }

    @Test
    void triggerMirroring() throws Exception {
        final CreateCredentialRequest credential = getCreateCredentialRequest(FOO_PROJ, null);
        ResponseEntity<PushResultDto> response =
                systemAdminClient.prepare()
                                 .post("/api/v1/projects/{proj}/credentials")
                                 .pathParam("proj", FOO_PROJ)
                                 .contentJson(credential)
                                 .asJson(PushResultDto.class)
                                 .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);

        final MirrorRequest newMirror = newMirror(credentialName(FOO_PROJ, PRIVATE_KEY_FILE));
        response = systemAdminClient.prepare()
                                    .post("/api/v1/projects/{proj}/repos/{repo}/mirrors")
                                    .pathParam("proj", FOO_PROJ)
                                    .pathParam("repo", BAR_REPO)
                                    .contentJson(newMirror)
                                    .asJson(PushResultDto.class)
                                    .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);

        for (int i = 0; i < 3; i++) {
            final ResponseEntity<MirrorResult> mirrorResponse =
                    systemAdminClient.prepare()
                                     .post("/api/v1/projects/{proj}/repos/{repo}/mirrors/{mirrorId}/run")
                                     .pathParam("proj", FOO_PROJ)
                                     .pathParam("repo", BAR_REPO)
                                     .pathParam("mirrorId", TEST_MIRROR_ID)
                                     .asJson(MirrorResult.class)
                                     .execute();

            assertThat(mirrorResponse.status()).isEqualTo(HttpStatus.OK);
            if (i == 0) {
                assertThat(mirrorResponse.content().mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
                assertThat(mirrorResponse.content().description())
                        .contains("'git+ssh://github.com/line/centraldogma-authtest.git#main' at 2");
            } else {
                assertThat(mirrorResponse.content().mirrorStatus()).isEqualTo(MirrorStatus.UP_TO_DATE);
                assertThat(mirrorResponse.content().description())
                        .contains("Repository 'foo/bar' already at");
            }
        }

        final String listenerKey = FOO_PROJ + '/' + TEST_MIRROR_ID + "/testId"; // test is the token appId
        assertThat(TestMirrorRunnerListener.startCount.get(listenerKey)).isEqualTo(3);
        final List<MirrorResult> results = TestMirrorRunnerListener.completions.get(listenerKey);
        final MirrorResult firstResult = results.get(0);
        assertThat(firstResult.mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
        assertThat(results.get(1).mirrorStatus()).isEqualTo(MirrorStatus.UP_TO_DATE);
        assertThat(results.get(2).mirrorStatus()).isEqualTo(MirrorStatus.UP_TO_DATE);
    }

    @Test
    void shouldControlGitMirrorAccess() throws Exception {
        ResponseEntity<MirrorAccessControl> accessResponse =
                systemAdminClient.prepare()
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

        final CreateCredentialRequest credential = getCreateCredentialRequest(FOO_PROJ, null);
        ResponseEntity<PushResultDto> response =
                systemAdminClient.prepare()
                                 .post("/api/v1/projects/{proj}/credentials")
                                 .pathParam("proj", FOO_PROJ)
                                 .contentJson(credential)
                                 .asJson(PushResultDto.class)
                                 .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);

        final MirrorRequest newMirror = newMirror(credentialName(FOO_PROJ, PRIVATE_KEY_FILE));
        response = systemAdminClient.prepare()
                                    .post("/api/v1/projects/{proj}/repos/{repo}/mirrors")
                                    .pathParam("proj", FOO_PROJ)
                                    .pathParam("repo", BAR_REPO)
                                    .contentJson(newMirror)
                                    .asJson(PushResultDto.class)
                                    .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);

        AggregatedHttpResponse mirrorResponse =
                systemAdminClient.prepare()
                                 .post("/api/v1/projects/{proj}/repos/{repo}/mirrors/{mirrorId}/run")
                                 .pathParam("proj", FOO_PROJ)
                                 .pathParam("repo", BAR_REPO)
                                 .pathParam("mirrorId", TEST_MIRROR_ID)
                                 .execute();
        // Mirror execution should be forbidden.
        assertThat(mirrorResponse.status()).isEqualTo(HttpStatus.FORBIDDEN);

        accessResponse = systemAdminClient.prepare()
                                          .post("/api/v1/mirror/access")
                                          .contentJson(new MirrorAccessControlRequest(
                                                  "centraldogma-authtest",
                                                  newMirror.remoteScheme() + "://" + newMirror.remoteUrl(),
                                                  true,
                                                  "allow centraldogma-authtest",
                                                  0))
                                          .asJson(MirrorAccessControl.class)
                                          .execute();
        assertThat(accessResponse.status()).isEqualTo(HttpStatus.CREATED);
        mirrorResponse = systemAdminClient.prepare()
                                          .post("/api/v1/projects/{proj}/repos/{repo}/mirrors/{mirrorId}/run")
                                          .pathParam("proj", FOO_PROJ)
                                          .pathParam("repo", BAR_REPO)
                                          .pathParam("mirrorId", TEST_MIRROR_ID)
                                          .execute();
        assertThat(mirrorResponse.status()).isEqualTo(HttpStatus.OK);
    }

    private static MirrorRequest newMirror(String credentialName) {
        return new MirrorRequest(TEST_MIRROR_ID,
                                 true,
                                 FOO_PROJ,
                                 null,
                                 "REMOTE_TO_LOCAL",
                                 BAR_REPO,
                                 "/",
                                 "git+ssh",
                                 "github.com/line/centraldogma-authtest.git",
                                 "/",
                                 "main",
                                 null,
                                 credentialName,
                                 null);
    }

    static CreateCredentialRequest getCreateCredentialRequest(String projectName, @Nullable String repoName)
            throws Exception {
        final byte[] privateKeyBytes =
                Resources.toByteArray(MirrorRunnerTest.class.getResource(PRIVATE_KEY_FILE));
        final byte[] publicKeyBytes =
                Resources.toByteArray(MirrorRunnerTest.class.getResource("ecdsa_256.openssh.pub"));
        final String privateKey = new String(privateKeyBytes, StandardCharsets.UTF_8).trim();
        final String publicKey = new String(publicKeyBytes, StandardCharsets.UTF_8).trim();

        return new CreateCredentialRequest(PRIVATE_KEY_FILE, new SshKeyCredential(
                repoName != null ? credentialName(projectName, repoName, PRIVATE_KEY_FILE)
                                 : credentialName(projectName, PRIVATE_KEY_FILE),
                "git",
                publicKey,
                privateKey,
                null));
    }
}
