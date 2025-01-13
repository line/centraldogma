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

package com.linecorp.centraldogma.server.internal.mirror;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.MirrorDto;
import com.linecorp.centraldogma.internal.api.v1.MirrorRequest;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.internal.credential.AccessTokenCredential;
import com.linecorp.centraldogma.server.internal.credential.NoneCredential;
import com.linecorp.centraldogma.server.internal.credential.PasswordCredential;
import com.linecorp.centraldogma.server.internal.credential.PublicKeyCredential;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class MirroringAndCredentialServiceV1Test {

    private static final String FOO_PROJ = "foo-proj";
    private static final String BAR_REPO = "bar-repo";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.systemAdministrators(USERNAME);
        }

        @Override
        protected void configureClient(ArmeriaCentralDogmaBuilder builder) {
            final String accessToken = getAccessToken(
                    WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort()),
                    USERNAME, PASSWORD);
            builder.accessToken(accessToken);
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject(FOO_PROJ).join();
            client.createRepository(FOO_PROJ, BAR_REPO).join();
        }
    };

    private BlockingWebClient systemAdminClient;
    private BlockingWebClient userClient;

    @BeforeEach
    void setUp() throws JsonProcessingException {
        final String systemAdminToken = getAccessToken(dogma.httpClient(), USERNAME, PASSWORD);
        systemAdminClient = WebClient.builder(dogma.httpClient().uri())
                                     .auth(AuthToken.ofOAuth2(systemAdminToken))
                                     .build()
                                     .blocking();

        final String userToken = getAccessToken(dogma.httpClient(), USERNAME2, PASSWORD2);
        userClient = WebClient.builder(dogma.httpClient().uri())
                              .auth(AuthToken.ofOAuth2(userToken))
                              .build()
                              .blocking();
    }

    @Test
    void cruTest() {
        setUpRole();
        createAndReadCredential();
        updateCredential();
        createAndReadMirror();
        updateMirror();
        rejectInvalidRepositoryUri();
        deleteMirror();
        deleteCredential();
    }

    private void rejectInvalidRepositoryUri() {
        final MirrorRequest newMirror =
                new MirrorRequest("invalid-mirror",
                                  true,
                                  FOO_PROJ,
                                  "5 * * * * ?",
                                  "REMOTE_TO_LOCAL",
                                  BAR_REPO,
                                  "/local-path/1/",
                                  "git+https",
                                  // Expect github.com/line/centraldogma-authtest.git
                                  "github.com:line/centraldogma-authtest.git",
                                  "/remote-path/1",
                                  "mirror-branch",
                                  ".my-env0\n.my-env1",
                                  "public-key-credential",
                                  null);
        final AggregatedHttpResponse response =
                userClient.prepare()
                          .post("/api/v1/projects/{proj}/repos/{repo}/mirrors")
                          .pathParam("proj", FOO_PROJ)
                          .pathParam("repo", BAR_REPO)
                          .contentJson(newMirror)
                          .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).contains("no host in remoteUri");
    }

    private void setUpRole() {
        final ResponseEntity<Revision> res =
                systemAdminClient.prepare()
                                 .post("/api/v1/metadata/{proj}/members")
                                 .pathParam("proj", FOO_PROJ)
                                 .contentJson(ImmutableMap.of("id", USERNAME2, "role", "OWNER"))
                                 .asJson(Revision.class)
                                 .execute();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
    }

    private void createAndReadCredential() {
        final List<Map<String, Object>> credentials = ImmutableList.of(
                ImmutableMap.of("type", "password", "id", "password-credential",
                                "username", "username-0", "password", "password-0"),
                ImmutableMap.of("type", "access_token", "id", "access-token-credential",
                                "accessToken", "secret-token-abc-1"),
                ImmutableMap.of("type", "public_key", "id", "public-key-credential",
                                "username", "username-2",
                                "publicKey", "public-key-2", "privateKey", "private-key-2",
                                "passphrase", "password-0"),
                ImmutableMap.of("type", "none", "id", "non-credential"));

        for (int i = 0; i < credentials.size(); i++) {
            final Map<String, Object> credential = credentials.get(i);
            final String credentialId = (String) credential.get("id");
            final ResponseEntity<PushResultDto> creationResponse =
                    userClient.prepare()
                              .post("/api/v1/projects/{proj}/credentials")
                              .pathParam("proj", FOO_PROJ)
                              .contentJson(credential)
                              .responseTimeoutMillis(0)
                              .asJson(PushResultDto.class)
                              .execute();
            assertThat(creationResponse.status()).isEqualTo(HttpStatus.CREATED);
            assertThat(creationResponse.content().revision().major()).isEqualTo(i + 2);

            for (BlockingWebClient client : ImmutableList.of(systemAdminClient, userClient)) {
                final boolean isSystemAdmin = client == systemAdminClient;
                final ResponseEntity<Credential> fetchResponse =
                        client.prepare()
                              .get("/api/v1/projects/{proj}/credentials/{id}")
                              .pathParam("proj", FOO_PROJ)
                              .pathParam("id", credentialId)
                              .responseTimeoutMillis(0)
                              .asJson(Credential.class)
                              .execute();
                final Credential credentialDto = fetchResponse.content();
                assertThat(credentialDto.id()).isEqualTo(credentialId);
                final String credentialType = (String) credential.get("type");
                if ("password".equals(credentialType)) {
                    final PasswordCredential actual = (PasswordCredential) credentialDto;
                    assertThat(actual.username()).isEqualTo(credential.get("username"));
                    if (isSystemAdmin) {
                        assertThat(actual.password()).isEqualTo(credential.get("password"));
                    } else {
                        assertThat(actual.password()).isEqualTo("****");
                    }
                } else if ("access_token".equals(credentialType)) {
                    final AccessTokenCredential actual = (AccessTokenCredential) credentialDto;
                    if (isSystemAdmin) {
                        assertThat(actual.accessToken()).isEqualTo(credential.get("accessToken"));
                    } else {
                        assertThat(actual.accessToken()).isEqualTo("****");
                    }
                } else if ("public_key".equals(credentialType)) {
                    final PublicKeyCredential actual = (PublicKeyCredential) credentialDto;
                    assertThat(actual.username()).isEqualTo(credential.get("username"));
                    assertThat(actual.publicKey()).isEqualTo(credential.get("publicKey"));
                    if (isSystemAdmin) {
                        assertThat(actual.rawPrivateKey()).isEqualTo(credential.get("privateKey"));
                        assertThat(actual.rawPassphrase()).isEqualTo(credential.get("passphrase"));
                    } else {
                        assertThat(actual.rawPrivateKey()).isEqualTo("****");
                        assertThat(actual.rawPassphrase()).isEqualTo("****");
                    }
                } else if ("none".equals(credentialType)) {
                    assertThat(credentialDto).isInstanceOf(NoneCredential.class);
                } else {
                    throw new AssertionError("Unexpected credential type: " + credential.getClass().getName());
                }
            }
        }
    }

    private void updateCredential() {
        final String credentialId = "public-key-credential";
        final Map<String, Object> credential =
                ImmutableMap.of("type", "public_key",
                                "id", credentialId,
                                "username", "updated-username-2",
                                "publicKey", "updated-public-key-2",
                                "privateKey", "updated-private-key-2",
                                "passphrase", "updated-password-0");
        final ResponseEntity<PushResultDto> creationResponse =
                userClient.prepare()
                          .put("/api/v1/projects/{proj}/credentials/{credentialId}")
                          .pathParam("proj", FOO_PROJ)
                          .pathParam("credentialId", credentialId)
                          .contentJson(credential)
                          .asJson(PushResultDto.class)
                          .execute();
        assertThat(creationResponse.status()).isEqualTo(HttpStatus.OK);

        for (BlockingWebClient client : ImmutableList.of(systemAdminClient, userClient)) {
            final boolean isSystemAdmin = client == systemAdminClient;
            final ResponseEntity<Credential> fetchResponse =
                    client.prepare()
                          .get("/api/v1/projects/{proj}/credentials/{id}")
                          .pathParam("proj", FOO_PROJ)
                          .pathParam("id", credentialId)
                          .asJson(Credential.class)
                          .execute();
            final PublicKeyCredential actual = (PublicKeyCredential) fetchResponse.content();
            assertThat(actual.id()).isEqualTo((String) credential.get("id"));
            assertThat(actual.username()).isEqualTo(credential.get("username"));
            assertThat(actual.publicKey()).isEqualTo(credential.get("publicKey"));
            if (isSystemAdmin) {
                assertThat(actual.rawPrivateKey()).isEqualTo(credential.get("privateKey"));
                assertThat(actual.rawPassphrase()).isEqualTo(credential.get("passphrase"));
            } else {
                assertThat(actual.rawPrivateKey()).isEqualTo("****");
                assertThat(actual.rawPassphrase()).isEqualTo("****");
            }
        }
    }

    private void createAndReadMirror() {
        for (int i = 0; i < 3; i++) {
            final MirrorRequest newMirror = newMirror("mirror-" + i);
            final ResponseEntity<PushResultDto> response0 =
                    userClient.prepare()
                              .post("/api/v1/projects/{proj}/repos/{repo}/mirrors")
                              .pathParam("proj", FOO_PROJ)
                              .pathParam("repo", BAR_REPO)
                              .contentJson(newMirror)
                              .asJson(PushResultDto.class)
                              .execute();
            assertThat(response0.status()).isEqualTo(HttpStatus.CREATED);
            final ResponseEntity<MirrorDto> response1 =
                    userClient.prepare()
                              .get("/api/v1/projects/{proj}/repos/{repo}/mirrors/{id}")
                              .pathParam("proj", FOO_PROJ)
                              .pathParam("repo", BAR_REPO)
                              .pathParam("id", newMirror.id())
                              .asJson(MirrorDto.class)
                              .execute();
            final MirrorDto savedMirror = response1.content();
            assertThat(savedMirror)
                    .usingRecursiveComparison()
                    .ignoringFields("allow")
                    .isEqualTo(newMirror);
        }

        // Make sure that the mirror with a port number in the remote URL can be created and read.
        final MirrorRequest mirrorWithPort = new MirrorRequest("mirror-with-port-3",
                                                               true,
                                                               FOO_PROJ,
                                                               "5 * * * * ?",
                                                               "REMOTE_TO_LOCAL",
                                                               BAR_REPO,
                                                               "/updated/local-path/",
                                                               "git+https",
                                                               "git.com:922/line/centraldogma-test.git",
                                                               "/updated/remote-path/",
                                                               "updated-mirror-branch",
                                                               ".updated-env",
                                                               "public-key-credential",
                                                               null);

        final ResponseEntity<PushResultDto> response0 =
                userClient.prepare()
                          .post("/api/v1/projects/{proj}/repos/{repo}/mirrors")
                          .pathParam("proj", FOO_PROJ)
                          .pathParam("repo", BAR_REPO)
                          .contentJson(mirrorWithPort)
                          .asJson(PushResultDto.class)
                          .execute();
        assertThat(response0.status()).isEqualTo(HttpStatus.CREATED);
        final ResponseEntity<MirrorDto> response1 =
                userClient.prepare()
                          .get("/api/v1/projects/{proj}/repos/{repo}/mirrors/{id}")
                          .pathParam("proj", FOO_PROJ)
                          .pathParam("repo", BAR_REPO)
                          .pathParam("id", mirrorWithPort.id())
                          .asJson(MirrorDto.class)
                          .execute();
        final MirrorDto savedMirror = response1.content();
        assertThat(savedMirror)
                .usingRecursiveComparison()
                .ignoringFields("allow")
                .isEqualTo(mirrorWithPort);
    }

    private void updateMirror() {
        final MirrorRequest mirror = new MirrorRequest("mirror-2",
                                                       true,
                                                       FOO_PROJ,
                                                       "5 * * * * ?",
                                                       "REMOTE_TO_LOCAL",
                                                       BAR_REPO,
                                                       "/updated/local-path/",
                                                       "git+https",
                                                       "github.com/line/centraldogma-updated.git",
                                                       "/updated/remote-path/",
                                                       "updated-mirror-branch",
                                                       ".updated-env",
                                                       "access-token-credential",
                                                       null);
        final ResponseEntity<PushResultDto> updateResponse =
                userClient.prepare()
                          .put("/api/v1/projects/{proj}/repos/{repo}/mirrors/{id}")
                          .pathParam("proj", FOO_PROJ)
                          .pathParam("repo", BAR_REPO)
                          .pathParam("id", mirror.id())
                          .contentJson(mirror)
                          .asJson(PushResultDto.class)
                          .execute();
        assertThat(updateResponse.status()).isEqualTo(HttpStatus.OK);
        final ResponseEntity<MirrorDto> fetchResponse =
                userClient.prepare()
                          .get("/api/v1/projects/{proj}/repos/{repo}/mirrors/{id}")
                          .pathParam("proj", FOO_PROJ)
                          .pathParam("repo", BAR_REPO)
                          .pathParam("id", mirror.id())
                          .asJson(MirrorDto.class)
                          .execute();
        final MirrorDto savedMirror = fetchResponse.content();
        assertThat(savedMirror)
                .usingRecursiveComparison()
                .ignoringFields("allow")
                .isEqualTo(mirror);
    }

    private void deleteMirror() {
        final String mirrorId = "mirror-2";
        assertThat(userClient.prepare()
                             .delete("/api/v1/projects/{proj}/repos/{repo}/mirrors/{id}")
                             .pathParam("proj", FOO_PROJ)
                             .pathParam("repo", BAR_REPO)
                             .pathParam("id", mirrorId)
                             .execute()
                             .status())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(userClient.prepare()
                             .get("/api/v1/projects/{proj}/repos/{repo}/mirrors/{id}")
                             .pathParam("proj", FOO_PROJ)
                             .pathParam("repo", BAR_REPO)
                             .pathParam("id", mirrorId)
                             .execute()
                             .status())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void deleteCredential() {
        final String credentialId = "public-key-credential";
        assertThat(userClient.prepare()
                             .delete("/api/v1/projects/{proj}/credentials/{id}")
                             .pathParam("proj", FOO_PROJ)
                             .pathParam("id", credentialId)
                             .execute()
                             .status())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(userClient.prepare()
                             .get("/api/v1/projects/{proj}/credentials/{id}")
                             .pathParam("proj", FOO_PROJ)
                             .pathParam("id", credentialId)
                             .execute()
                             .status())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static MirrorRequest newMirror(String id) {
        return new MirrorRequest(id,
                                 true,
                                 FOO_PROJ,
                                 "5 * * * * ?",
                                 "REMOTE_TO_LOCAL",
                                 BAR_REPO,
                                 "/local-path/" + id + '/',
                                 "git+https",
                                 "github.com/line/centraldogma-authtest.git",
                                 "/remote-path/" + id + '/',
                                 "mirror-branch",
                                 ".my-env0\n.my-env1",
                                 "public-key-credential",
                                 null);
    }
}
