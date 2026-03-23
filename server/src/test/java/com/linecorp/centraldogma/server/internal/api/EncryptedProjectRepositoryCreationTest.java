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
package com.linecorp.centraldogma.server.internal.api;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.REPOS;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.EncryptionConfig;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class EncryptedProjectRepositoryCreationTest {

    private static final String PROJECT_NAME = "encProj";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.encryption(new EncryptionConfig(true, true, "kekId"))
                   .systemAdministrators(USERNAME)
                   .authProviderFactory(new TestAuthProviderFactory());
        }

        @Override
        protected String accessToken() {
            return getAccessToken(WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort()),
                                  USERNAME, PASSWORD, "testAppId", true, false, true);
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject(PROJECT_NAME).join();
        }
    };

    @Test
    void shouldCreateEncryptedRepoWhenProjectIsEncrypted() {
        // The project's dogma repository should already be encrypted because
        // the server was started with encryption enabled.
        final Repository dogmaRepo =
                dogma.projectManager().get(PROJECT_NAME).repos().get(Project.REPO_DOGMA);
        assertThat(dogmaRepo.isEncrypted()).isTrue();

        // Create a repository WITHOUT explicitly requesting encryption (encrypt: false).
        final AggregatedHttpResponse response = createRepository(dogma.httpClient(), "nonEncryptRepo");
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);

        // The newly created repository should still be encrypted because the project is encrypted.
        final Repository newRepo =
                dogma.projectManager().get(PROJECT_NAME).repos().get("nonEncryptRepo");
        assertThat(newRepo.isEncrypted()).isTrue();
    }

    @Test
    void shouldCreateEncryptedRepoWhenExplicitlyRequested() {
        // Create a repository WITH explicitly requesting encryption (encrypt: true).
        final AggregatedHttpResponse response =
                createEncryptedRepository(dogma.httpClient(), "explicitEncRepo");
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);

        final Repository newRepo =
                dogma.projectManager().get(PROJECT_NAME).repos().get("explicitEncRepo");
        assertThat(newRepo.isEncrypted()).isTrue();
    }

    private static AggregatedHttpResponse createRepository(WebClient client, String repoName) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST,
                                                         PROJECTS_PREFIX + '/' + PROJECT_NAME + REPOS,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final String body = "{\"name\": \"" + repoName + "\"}";
        return client.execute(headers, body).aggregate().join();
    }

    private static AggregatedHttpResponse createEncryptedRepository(WebClient client, String repoName) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST,
                                                         PROJECTS_PREFIX + '/' + PROJECT_NAME + REPOS,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final String body = "{\"name\": \"" + repoName + "\", \"encrypt\": true}";
        return client.execute(headers, body).aggregate().join();
    }
}
