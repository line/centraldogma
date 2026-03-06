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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.MirrorDto;
import com.linecorp.centraldogma.internal.api.v1.MirrorRequest;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.credential.CreateCredentialRequest;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.credential.CredentialType;
import com.linecorp.centraldogma.server.internal.credential.AccessTokenCredential;
import com.linecorp.centraldogma.server.internal.credential.NoneCredential;
import com.linecorp.centraldogma.server.internal.credential.PasswordCredential;
import com.linecorp.centraldogma.server.internal.credential.SshKeyCredential;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class MirroringAndCredentialServiceV1Test {

    private static final String FOO_PROJ = "foo-proj";
    private static final String BAR_REPO = "bar-repo";
    private static final String BAR2_REPO = "bar2-repo";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.systemAdministrators(USERNAME);
        }

        @Override
        protected String accessToken() {
            return getAccessToken(
                    WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort()),
                    USERNAME, PASSWORD, true);
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject(FOO_PROJ).join();
            client.createRepository(FOO_PROJ, BAR_REPO).join();
            client.createRepository(FOO_PROJ, BAR2_REPO).join();
        }
    };

    private static BlockingWebClient systemAdminClient;
    private static BlockingWebClient userClient;

    @BeforeAll
    static void setUp() throws JsonProcessingException {
        final String systemAdminToken = getAccessToken(dogma.httpClient(), USERNAME, PASSWORD, "appId1", true);
        systemAdminClient = WebClient.builder(dogma.httpClient().uri())
                                     .auth(AuthToken.ofOAuth2(systemAdminToken))
                                     .build()
                                     .blocking();

        final String userToken = getAccessToken(dogma.httpClient(), USERNAME, PASSWORD, "appId2", false);
        userClient = WebClient.builder(dogma.httpClient().uri())
                              .auth(AuthToken.ofOAuth2(userToken))
                              .build()
                              .blocking();
        setUpRole();
    }

    @Test
    void crudTest() {
        createAndReadCredential();
        updateCredential();
        createAndReadMirror(BAR_REPO);
        createAndReadMirror(BAR2_REPO);
        ResponseEntity<List<MirrorDto>> response =
                userClient.prepare()
                          .get("/api/v1/projects/{proj}/repos/{repo}/mirrors")
                          .pathParam("proj", FOO_PROJ)
                          .pathParam("repo", BAR_REPO)
                          .asJson(new TypeReference<List<MirrorDto>>() {})
                          .execute();
        assertThat(response.content().size()).isEqualTo(4); // mirror-0, mirror-1, mirror-2, mirror-with-port-3
        assertThat(response.content().stream().map(MirrorRequest::localRepo)
                           .distinct()
                           .collect(toImmutableList())).containsExactly(BAR_REPO);
        response = userClient.prepare()
                             .get("/api/v1/projects/{proj}/mirrors")
                             .pathParam("proj", FOO_PROJ)
                             .asJson(new TypeReference<List<MirrorDto>>() {})
                             .execute();
        assertThat(response.content().size()).isEqualTo(8);
        assertThat(response.content().stream().map(MirrorRequest::localRepo)
                           .distinct()
                           .collect(toImmutableList())).containsExactlyInAnyOrder(BAR_REPO, BAR2_REPO);
        updateMirror();
        rejectInvalidRepositoryUri();
        deleteMirror(BAR_REPO);
        deleteMirror(BAR2_REPO);
        deleteCredential();
    }

    @Test
    void repoAndProjectCredentialsUsed() {
        // Make sure there are no project and repo credentials.
        AggregatedHttpResponse response =
                systemAdminClient.prepare()
                                 .get("/api/v1/projects/{proj}/credentials")
                                 .pathParam("proj", FOO_PROJ)
                                 .execute();
        assertThat(response.status()).isSameAs(HttpStatus.NO_CONTENT);
        response = systemAdminClient.prepare()
                                    .get("/api/v1/projects/{proj}/repos/{repo}/credentials")
                                    .pathParam("proj", FOO_PROJ)
                                    .pathParam("repo", BAR_REPO)
                                    .execute();
        assertThat(response.status()).isSameAs(HttpStatus.NO_CONTENT);

        final CreateCredentialRequest repoCredentialRequest = new CreateCredentialRequest(
                "REPO_CREDENTIAL", new AccessTokenCredential(
                        credentialName(FOO_PROJ, BAR_REPO, "REPO_CREDENTIAL"), "secret-repo-token")
        );
        final ResponseEntity<PushResultDto> creationResponse =
                userClient.prepare()
                          .post("/api/v1/projects/{proj}/repos/{repo}/credentials")
                          .pathParam("proj", FOO_PROJ)
                          .pathParam("repo", BAR_REPO)
                          .contentJson(repoCredentialRequest)
                          .asJson(PushResultDto.class)
                          .execute();
        assertThat(creationResponse.status()).isEqualTo(HttpStatus.CREATED);
        final CreateCredentialRequest projectCredentialRequest = new CreateCredentialRequest(
                "PROJECT_CREDENTIAL", new AccessTokenCredential(
                credentialName(FOO_PROJ, "PROJECT_CREDENTIAL"), "secret-repo-token")
        );
        createProjectCredential(projectCredentialRequest);

        // Create mirrors.
        final MirrorRequest newMirror1 =
                newMirror(BAR_REPO, "mirror-1", credentialName(FOO_PROJ, BAR_REPO, "REPO_CREDENTIAL"));
        createMirror(BAR_REPO, newMirror1);
        final MirrorRequest newMirror2 =
                newMirror(BAR_REPO, "mirror-2", credentialName(FOO_PROJ, "PROJECT_CREDENTIAL"));
        createMirror(BAR_REPO, newMirror2);

        // Read mirrors.
        final MirrorDto mirror1 = getMirror(BAR_REPO, newMirror1.id());
        assertThat(mirror1.credentialName()).isEqualTo(
                credentialName(FOO_PROJ, BAR_REPO, "REPO_CREDENTIAL"));
        final MirrorDto mirror2 = getMirror(BAR_REPO, newMirror2.id());
        assertThat(mirror2.credentialName()).isEqualTo(
                credentialName(FOO_PROJ, "PROJECT_CREDENTIAL"));
    }

    private static void rejectInvalidRepositoryUri() {
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
                                  credentialName(FOO_PROJ, "ssh-key-credential"),
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

    private static void setUpRole() {
        final ResponseEntity<Revision> res =
                systemAdminClient.prepare()
                                 .post("/api/v1/metadata/{proj}/appIdentities")
                                 .pathParam("proj", FOO_PROJ)
                                 .contentJson(ImmutableMap.of("id", "appId2", "role", "OWNER"))
                                 .asJson(Revision.class)
                                 .execute();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
    }

    private static void createAndReadCredential() {
        final List<CreateCredentialRequest> requests = ImmutableList.of(
                new CreateCredentialRequest(
                        "password-credential",
                        new PasswordCredential(credentialName(FOO_PROJ, "password-credential"),
                                               "username-0", "password-0")),
                new CreateCredentialRequest(
                        "access-token-credential",
                        new AccessTokenCredential(credentialName(FOO_PROJ, "access-token-credential"),
                                                  "secret-token-abc-1")),
                new CreateCredentialRequest(
                        "ssh-key-credential",
                        new SshKeyCredential(credentialName(FOO_PROJ, "ssh-key-credential"),
                                             "username-2", "public-key-2", "private-key-2", "password-0")),
                new CreateCredentialRequest(
                        "non-credential",
                        new NoneCredential(credentialName(FOO_PROJ, "non-credential")))
        );

        for (int i = 0; i < requests.size(); i++) {
            final CreateCredentialRequest request = requests.get(i);
            createProjectCredential(request);
            final Credential credential = request.credential();
            final String credentialName = credential.name();

            for (BlockingWebClient client : ImmutableList.of(systemAdminClient, userClient)) {
                final boolean isSystemAdmin = client == systemAdminClient;
                final ResponseEntity<Credential> fetchResponse =
                        client.prepare()
                              .get("/api/v1/" + credentialName)
                              .responseTimeoutMillis(0)
                              .asJson(Credential.class)
                              .execute();
                final Credential credentialDto = fetchResponse.content();
                assertThat(credentialDto.name()).isEqualTo(credentialName);
                final CredentialType credentialType = credential.type();
                if (credentialType == CredentialType.PASSWORD) {
                    final PasswordCredential actual = (PasswordCredential) credentialDto;
                    assertThat(credential).isInstanceOf(PasswordCredential.class);
                    assertThat(actual.username()).isEqualTo(((PasswordCredential) credential).username());
                    if (isSystemAdmin) {
                        assertThat(actual.password()).isEqualTo(((PasswordCredential) credential).password());
                    } else {
                        assertThat(actual.password()).isEqualTo("****");
                    }
                } else if (credentialType == CredentialType.ACCESS_TOKEN) {
                    final AccessTokenCredential actual = (AccessTokenCredential) credentialDto;
                    assertThat(credential).isInstanceOf(AccessTokenCredential.class);
                    if (isSystemAdmin) {
                        assertThat(actual.accessToken()).isEqualTo(
                                ((AccessTokenCredential) credential).accessToken());
                    } else {
                        assertThat(actual.accessToken()).isEqualTo("****");
                    }
                } else if (credentialType == CredentialType.SSH_KEY) {
                    final SshKeyCredential actual = (SshKeyCredential) credentialDto;
                    assertThat(credential).isInstanceOf(SshKeyCredential.class);
                    final SshKeyCredential sshKeyCredential = (SshKeyCredential) credential;
                    assertThat(actual.username()).isEqualTo(sshKeyCredential.username());
                    assertThat(actual.publicKey()).isEqualTo(sshKeyCredential.publicKey());
                    if (isSystemAdmin) {
                        assertThat(actual.rawPrivateKey()).isEqualTo(sshKeyCredential.rawPrivateKey());
                        assertThat(actual.rawPassphrase()).isEqualTo(sshKeyCredential.rawPassphrase());
                    } else {
                        assertThat(actual.rawPrivateKey()).isEqualTo("****");
                        assertThat(actual.rawPassphrase()).isEqualTo("****");
                    }
                } else if (credentialType == CredentialType.NONE) {
                    assertThat(credentialDto).isInstanceOf(NoneCredential.class);
                } else {
                    throw new AssertionError("Unexpected credential type: " + credential.getClass().getName());
                }
            }
        }
    }

    private static void createProjectCredential(CreateCredentialRequest request) {
        final ResponseEntity<PushResultDto> creationResponse =
                userClient.prepare()
                          .post("/api/v1/projects/{proj}/credentials")
                          .pathParam("proj", FOO_PROJ)
                          .contentJson(request)
                          .responseTimeoutMillis(0)
                          .asJson(PushResultDto.class)
                          .execute();
        assertThat(creationResponse.status()).isEqualTo(HttpStatus.CREATED);
    }

    private static void updateCredential() {
        final String credentialId = "ssh-key-credential";
        final Map<String, String> credential =
                ImmutableMap.of("type", CredentialType.SSH_KEY.name(),
                                "name", credentialName(FOO_PROJ, credentialId),
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
            final SshKeyCredential actual = (SshKeyCredential) fetchResponse.content();
            assertThat(actual.name()).isEqualTo(credential.get("name"));
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

    private static void createAndReadMirror(String repoName) {
        for (int i = 0; i < 3; i++) {
            final MirrorRequest newMirror = newMirror(repoName, "mirror-" + i);
            createMirror(repoName, newMirror);
            final MirrorDto savedMirror = getMirror(repoName, newMirror.id());
            assertThat(savedMirror)
                    .usingRecursiveComparison()
                    .ignoringFields("allow")
                    .isEqualTo(newMirror);
        }

        // Make sure that the mirror with a port number in the remote URL can be created and read.
        final MirrorRequest mirrorWithPort = new MirrorRequest(
                "mirror-with-port-3",
                true,
                FOO_PROJ,
                "5 * * * * ?",
                "REMOTE_TO_LOCAL",
                repoName,
                "/updated/local-path/",
                "git+https",
                "git.com:922/line/centraldogma-test.git",
                "/updated/remote-path/",
                "updated-mirror-branch",
                ".updated-env",
                credentialName(FOO_PROJ, "ssh-key-credential"),
                null);
        createMirror(repoName, mirrorWithPort);
        final MirrorDto savedMirror = getMirror(repoName, mirrorWithPort.id());
        assertThat(savedMirror)
                .usingRecursiveComparison()
                .ignoringFields("allow")
                .isEqualTo(mirrorWithPort);
    }

    private static void createMirror(String repoName, MirrorRequest newMirror) {
        final ResponseEntity<PushResultDto> response =
                userClient.prepare()
                          .post("/api/v1/projects/{proj}/repos/{repo}/mirrors")
                          .pathParam("proj", FOO_PROJ)
                          .pathParam("repo", repoName)
                          .contentJson(newMirror)
                          .asJson(PushResultDto.class)
                          .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
    }

    private static MirrorDto getMirror(String repoName, String mirrorId) {
        final ResponseEntity<MirrorDto> response1 =
                userClient.prepare()
                          .get("/api/v1/projects/{proj}/repos/{repo}/mirrors/{id}")
                          .pathParam("proj", FOO_PROJ)
                          .pathParam("repo", repoName)
                          .pathParam("id", mirrorId)
                          .asJson(MirrorDto.class)
                          .execute();
        assertThat(response1.status()).isEqualTo(HttpStatus.OK);
        return response1.content();
    }

    private static void updateMirror() {
        final MirrorRequest mirror = new MirrorRequest(
                "mirror-2",
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
                credentialName(FOO_PROJ, "access-token-credential"),
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
        final MirrorDto savedMirror = getMirror(BAR_REPO, mirror.id());
        assertThat(savedMirror)
                .usingRecursiveComparison()
                .ignoringFields("allow")
                .isEqualTo(mirror);
    }

    private static void deleteMirror(String repoName) {
        for (int i = 0; i < 3; i++) {
            final String mirrorId = "mirror-" + i;
            assertThat(userClient.prepare()
                                 .delete("/api/v1/projects/{proj}/repos/{repo}/mirrors/{id}")
                                 .pathParam("proj", FOO_PROJ)
                                 .pathParam("repo", repoName)
                                 .pathParam("id", mirrorId)
                                 .execute()
                                 .status())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(userClient.prepare()
                                 .get("/api/v1/projects/{proj}/repos/{repo}/mirrors/{id}")
                                 .pathParam("proj", FOO_PROJ)
                                 .pathParam("repo", repoName)
                                 .pathParam("id", mirrorId)
                                 .execute()
                                 .status())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private static void deleteCredential() {
        final Set<String> credentialIds = ImmutableSet.of("password-credential",
                                                          "access-token-credential",
                                                          "ssh-key-credential",
                                                          "non-credential");
        for (String credentialId : credentialIds) {
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
    }

    private static MirrorRequest newMirror(String repoName, String id) {
        return newMirror(repoName, id, credentialName(FOO_PROJ, "ssh-key-credential"));
    }

    private static MirrorRequest newMirror(String repoName, String id, String credentialName) {
        return new MirrorRequest(id,
                                 true,
                                 FOO_PROJ,
                                 "5 * * * * ?",
                                 "REMOTE_TO_LOCAL",
                                 repoName,
                                 "/local-path/" + id + '/',
                                 "git+https",
                                 "github.com/line/centraldogma-authtest.git",
                                 "/remote-path/" + id + '/',
                                 "mirror-branch",
                                 ".my-env0\n.my-env1",
                                 credentialName,
                                 null);
    }
}
