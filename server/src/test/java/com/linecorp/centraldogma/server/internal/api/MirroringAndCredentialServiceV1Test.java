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

package com.linecorp.centraldogma.server.internal.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.api.v1.MirrorDto;
import com.linecorp.centraldogma.server.internal.mirror.credential.AccessTokenMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.NoneMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PasswordMirrorCredential;
import com.linecorp.centraldogma.server.internal.mirror.credential.PublicKeyMirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class MirroringAndCredentialServiceV1Test {

    private static final String FOO_PROJ = "foo-proj";
    private static final String BAR_REPO = "bar-repo";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject(FOO_PROJ).join();
            client.createRepository(FOO_PROJ, BAR_REPO).join();
        }
    };

    private final List<String> hostnamePatterns = ImmutableList.of("github.com");
    private BlockingWebClient client;

    @BeforeEach
    void setUp() {
        client = dogma.blockingHttpClient();
    }

    @Test
    void cruTest() throws JsonParseException {
        createAndReadCredential();
        updateCredential();
        createAndReadMirror();
        updateMirror();
    }

    private void createAndReadCredential() {
        final List<Map<String, Object>> credentials = ImmutableList.of(
                ImmutableMap.of("type", "password", "id", "password-credential",
                                "hostnamePatterns", hostnamePatterns,
                                "username", "username-0", "password", "password-0"),
                ImmutableMap.of("type", "access_token", "id", "access-token-credential", "hostnamePatterns",
                                hostnamePatterns, "accessToken", "secret-token-abc-1"),
                ImmutableMap.of("type", "public_key", "id", "public-key-credential",
                                "hostnamePatterns", hostnamePatterns, "username", "username-2",
                                "publicKey", "public-key-2", "privateKey", "private-key-2",
                                "passphrase", "password-0"),
                ImmutableMap.of("type", "none", "id", "non-credential", "hostnamePatterns", hostnamePatterns));

        for (int i = 0; i < credentials.size(); i++) {
            final Map<String, Object> credential = credentials.get(i);
            final ResponseEntity<Revision> creationResponse =
                    client.prepare()
                          .post("/api/v1/projects/{proj}/credentials")
                          .pathParam("proj", FOO_PROJ)
                          .header(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                          .contentJson(credential)
                          .responseTimeoutMillis(0)
                          .asJson(Revision.class)
                          .execute();
            assertThat(creationResponse.status()).isEqualTo(HttpStatus.CREATED);
            assertThat(creationResponse.content().major()).isEqualTo(i + 2);

            final ResponseEntity<MirrorCredential> fetchResponse =
                    client.prepare()
                          .get("/api/v1/projects/{proj}/credentials/{index}")
                          .pathParam("proj", FOO_PROJ)
                          .pathParam("index", i)
                          .responseTimeoutMillis(0)
                          .header(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                          .asJson(MirrorCredential.class)
                          .execute();
            final MirrorCredential credentialDto = fetchResponse.content();
            assertThat(credentialDto.id()).hasValue((String) credential.get("id"));
            assertThat(credentialDto.hostnamePatterns().stream().map(Pattern::pattern)).isEqualTo(
                    credential.get("hostnamePatterns"));
            final String credentialType = (String) credential.get("type");
            if ("password".equals(credentialType)) {
                final PasswordMirrorCredential actual = (PasswordMirrorCredential) credentialDto;
                assertThat(actual.username()).isEqualTo(credential.get("username"));
                assertThat(actual.password()).isEqualTo(credential.get("password"));
            } else if ("access_token".equals(credentialType)) {
                final AccessTokenMirrorCredential actual = (AccessTokenMirrorCredential) credentialDto;
                assertThat(actual.accessToken()).isEqualTo(credential.get("accessToken"));
            } else if ("public_key".equals(credentialType)) {
                final PublicKeyMirrorCredential actual = (PublicKeyMirrorCredential) credentialDto;
                assertThat(actual.username()).isEqualTo(credential.get("username"));
                assertThat(actual.publicKeyString()).isEqualTo(credential.get("publicKey"));
                assertThat(actual.privateKeyString()).isEqualTo(credential.get("privateKey"));
                assertThat(actual.passphraseString()).isEqualTo(credential.get("passphrase"));
            } else if ("none".equals(credentialType)) {
                assertThat(credentialDto).isInstanceOf(NoneMirrorCredential.class);
            } else {
                throw new AssertionError("Unexpected credential type: " + credential.getClass().getName());
            }
        }
    }

    private void updateCredential() {
        final List<String> hostnamePatterns = ImmutableList.of("gitlab.com");
        final Map<String, Object> credential =
                ImmutableMap.of("type", "public_key",
                                "id", "public-key-credential",
                                "hostnamePatterns", hostnamePatterns,
                                "username", "updated-username-2",
                                "publicKey", "updated-public-key-2",
                                "privateKey", "updated-private-key-2",
                                "passphrase", "updated-password-0");
        final ResponseEntity<Revision> creationResponse =
                client.prepare()
                      .put("/api/v1/projects/{proj}/credentials/{index}")
                      .pathParam("proj", FOO_PROJ)
                      .pathParam("index", 2)
                      .header(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                      .contentJson(credential)
                      .asJson(Revision.class)
                      .execute();
        assertThat(creationResponse.status()).isEqualTo(HttpStatus.OK);

        final ResponseEntity<MirrorCredential> fetchResponse =
                client.prepare()
                      .get("/api/v1/projects/{proj}/credentials/{index}")
                      .pathParam("proj", FOO_PROJ)
                      .pathParam("index", 2)
                      .header(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                      .asJson(MirrorCredential.class)
                      .execute();
        final PublicKeyMirrorCredential actual = (PublicKeyMirrorCredential) fetchResponse.content();
        assertThat(actual.id()).hasValue((String) credential.get("id"));
        assertThat(actual.hostnamePatterns().stream().map(Pattern::pattern)).isEqualTo(hostnamePatterns);
        assertThat(actual.username()).isEqualTo(credential.get("username"));
        assertThat(actual.publicKeyString()).isEqualTo(credential.get("publicKey"));
        assertThat(actual.privateKeyString()).isEqualTo(credential.get("privateKey"));
        assertThat(actual.passphraseString()).isEqualTo(credential.get("passphrase"));
    }

    private void createAndReadMirror() throws JsonParseException {
        for (int i = 0; i < 3; i++) {
            final MirrorDto newMirror = newMirror("mirror-" + i);
            final ResponseEntity<Revision> response0 =
                    client.prepare()
                          .post("/api/v1/projects/{proj}/mirrors")
                          .pathParam("proj", FOO_PROJ)
                          .header(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                          .contentJson(newMirror)
                          .asJson(Revision.class)
                          .execute();
            assertThat(response0.status()).isEqualTo(HttpStatus.CREATED);
            // TODO(ikhoon): Migrate to using id instead of index.
            final ResponseEntity<MirrorDto> response1 =
                    client.prepare()
                          .get("/api/v1/projects/{proj}/mirrors/{index}")
                          .pathParam("proj", FOO_PROJ)
                          .pathParam("index", i)
                          .header(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                          .asJson(MirrorDto.class)
                          .execute();
            final MirrorDto savedMirror = response1.content();
            assertThat(savedMirror)
                    .usingRecursiveComparison()
                    .ignoringFields("index")
                    .isEqualTo(newMirror);
        }
    }

    private void updateMirror() {
        final MirrorDto mirror = new MirrorDto("mirror-2",
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
                                               true);
        // TODO(ikhoon): Migrate index to id.
        final ResponseEntity<Revision> updateResponse =
                client.prepare()
                      .put("/api/v1/projects/{proj}/mirrors/{index}")
                      .pathParam("proj", FOO_PROJ)
                      .pathParam("index", 2)
                      .header(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                      .contentJson(mirror)
                      .asJson(Revision.class)
                      .execute();
        assertThat(updateResponse.status()).isEqualTo(HttpStatus.OK);
        final ResponseEntity<MirrorDto> fetchResponse =
                client.prepare()
                      .get("/api/v1/projects/{proj}/mirrors/{index}")
                      .pathParam("proj", FOO_PROJ)
                      .pathParam("index", 2)
                      .header(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                      .asJson(MirrorDto.class)
                      .execute();
        final MirrorDto savedMirror = fetchResponse.content();
        assertThat(savedMirror)
                .usingRecursiveComparison()
                .ignoringFields("index")
                .isEqualTo(mirror);
    }

    private static MirrorDto newMirror(String id) {
        return new MirrorDto(id,
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
                             true);
    }
}
