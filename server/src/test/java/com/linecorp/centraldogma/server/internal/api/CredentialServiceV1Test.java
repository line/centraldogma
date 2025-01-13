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
package com.linecorp.centraldogma.server.internal.api;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.internal.credential.AccessTokenCredential;
import com.linecorp.centraldogma.server.internal.credential.NoneCredential;
import com.linecorp.centraldogma.server.internal.credential.PasswordCredential;
import com.linecorp.centraldogma.server.internal.credential.PublicKeyCredential;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class CredentialServiceV1Test {

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
        protected void configureHttpClient(WebClientBuilder builder) {
            // TODO(minwoox): Override accessToken to provide token to both WebClient and CentralDogma client.
            final String accessToken = getAccessToken(
                    WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort()),
                    USERNAME, PASSWORD);
            builder.auth(AuthToken.ofOAuth2(accessToken));
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

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void createAndReadCredential(boolean projectLevel) {
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

        final BlockingWebClient client = dogma.blockingHttpClient();
        for (int i = 0; i < credentials.size(); i++) {
            final Map<String, Object> credential = credentials.get(i);
            final String credentialId = (String) credential.get("id");
            final ResponseEntity<PushResultDto> creationResponse =
                    client.prepare()
                          .post(projectLevel ? "/api/v1/projects/{proj}/credentials"
                                             : "/api/v1/projects/{proj}/repos/" + BAR_REPO + "/credentials")
                          .pathParam("proj", FOO_PROJ)
                          .contentJson(credential)
                          .responseTimeoutMillis(0)
                          .asJson(PushResultDto.class)
                          .execute();
            assertThat(creationResponse.status()).isEqualTo(HttpStatus.CREATED);

            final ResponseEntity<Credential> fetchResponse =
                    client.prepare()
                          .get(projectLevel ? "/api/v1/projects/{proj}/credentials/{id}"
                                            : "/api/v1/projects/{proj}/repos/" + BAR_REPO + "/credentials/{id}")
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
                assertThat(actual.password()).isEqualTo(credential.get("password"));
            } else if ("access_token".equals(credentialType)) {
                final AccessTokenCredential actual = (AccessTokenCredential) credentialDto;
                assertThat(actual.accessToken()).isEqualTo(credential.get("accessToken"));
            } else if ("public_key".equals(credentialType)) {
                final PublicKeyCredential actual = (PublicKeyCredential) credentialDto;
                assertThat(actual.username()).isEqualTo(credential.get("username"));
                assertThat(actual.publicKey()).isEqualTo(credential.get("publicKey"));
                assertThat(actual.rawPrivateKey()).isEqualTo(credential.get("privateKey"));
                assertThat(actual.rawPassphrase()).isEqualTo(credential.get("passphrase"));
            } else if ("none".equals(credentialType)) {
                assertThat(credentialDto).isInstanceOf(NoneCredential.class);
            } else {
                throw new AssertionError("Unexpected credential type: " + credential.getClass().getName());
            }
        }
    }
}
