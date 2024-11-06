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

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.io.Resources;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.internal.api.v1.MirrorDto;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.internal.credential.PublicKeyCredential;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirrorStatus;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class MirrorRunnerTest {

    private static final String FOO_PROJ = "foo";
    private static final String BAR_REPO = "bar";
    private static final String PRIVATE_KEY_FILE = "ecdsa_256.openssh";
    private static final String TEST_MIRROR_ID = "test-mirror";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.systemAdministrators(USERNAME);
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject(FOO_PROJ).join();
            client.createRepository(FOO_PROJ, BAR_REPO).join();
        }
    };

    private BlockingWebClient systemAdminClient;

    @BeforeEach
    void setUp() throws Exception {
        final String adminToken = getAccessToken(dogma.httpClient(), USERNAME, PASSWORD);
        systemAdminClient = WebClient.builder(dogma.httpClient().uri())
                                     .auth(AuthToken.ofOAuth2(adminToken))
                                     .build()
                                     .blocking();
    }

    @Test
    void triggerMirroring() throws Exception {
        final PublicKeyCredential credential = getCredential();
        ResponseEntity<PushResultDto> response =
                systemAdminClient.prepare()
                                 .post("/api/v1/projects/{proj}/credentials")
                                 .pathParam("proj", FOO_PROJ)
                                 .contentJson(credential)
                                 .asJson(PushResultDto.class)
                                 .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);

        final MirrorDto newMirror = newMirror();
        response = systemAdminClient.prepare()
                                    .post("/api/v1/projects/{proj}/mirrors")
                                    .pathParam("proj", FOO_PROJ)
                                    .contentJson(newMirror)
                                    .asJson(PushResultDto.class)
                                    .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);

        for (int i = 0; i < 3; i++) {
            final ResponseEntity<MirrorResult> mirrorResponse =
                    systemAdminClient.prepare()
                                     .post("/api/v1/projects/{proj}/mirrors/{mirrorId}/run")
                                     .pathParam("proj", FOO_PROJ)
                                     .pathParam("mirrorId", TEST_MIRROR_ID)
                                     .asJson(MirrorResult.class)
                                     .execute();

            assertThat(mirrorResponse.status()).isEqualTo(HttpStatus.OK);
            if (i == 0) {
                assertThat(mirrorResponse.content().mirrorStatus()).isEqualTo(MirrorStatus.SUCCESS);
                assertThat(mirrorResponse.content().description())
                        .contains("'git+ssh://github.com/line/centraldogma-authtest.git#main' to " +
                                  "the repository 'bar', revision: 2");
            } else {
                assertThat(mirrorResponse.content().mirrorStatus()).isEqualTo(MirrorStatus.UP_TO_DATE);
                assertThat(mirrorResponse.content().description())
                        .contains("Repository 'foo/bar' already at");
            }
        }
    }

    private static MirrorDto newMirror() {
        return new MirrorDto(TEST_MIRROR_ID,
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
                             PRIVATE_KEY_FILE);
    }

    private static PublicKeyCredential getCredential() throws Exception {
        final String publicKeyFile = "ecdsa_256.openssh.pub";

        final byte[] privateKeyBytes =
                Resources.toByteArray(GitMirrorAuthTest.class.getResource(PRIVATE_KEY_FILE));
        final byte[] publicKeyBytes =
                Resources.toByteArray(GitMirrorAuthTest.class.getResource(publicKeyFile));
        final String privateKey = new String(privateKeyBytes, StandardCharsets.UTF_8).trim();
        final String publicKey = new String(publicKeyBytes, StandardCharsets.UTF_8).trim();

        return new PublicKeyCredential(
                PRIVATE_KEY_FILE,
                true,
                "git",
                publicKey,
                privateKey,
                null);
    }
}
