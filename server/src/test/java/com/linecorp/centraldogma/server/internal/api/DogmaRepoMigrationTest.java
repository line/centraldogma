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
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.EncryptionConfig;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class DogmaRepoMigrationTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.encryption(new EncryptionConfig(true, false, "kekId"))
                   .systemAdministrators(USERNAME)
                   .authProviderFactory(new TestAuthProviderFactory());
        }

        @Override
        protected String accessToken() {
            return getAccessToken(WebClient.of("http://127.0.0.1:" + dogma.serverAddress().getPort()),
                                  USERNAME, PASSWORD, "testAppId", true);
        }
    };

    @Test
    void migrateDogmaProjectDogmaRepository() {
        final String projectName = InternalProjectInitializer.INTERNAL_PROJECT_DOGMA;
        final String repoName = Project.REPO_DOGMA;

        // The dogma repo should not be encrypted initially.
        final Repository dogmaRepo =
                dogma.projectManager().get(projectName).repos().get(repoName);
        assertThat(dogmaRepo.isEncrypted()).isFalse();

        // Migrate the dogma/dogma repository via the API.
        // This should succeed without setting the repository to read-only.
        final AggregatedHttpResponse response = migrateRepository(dogma.httpClient(),
                                                                   projectName, repoName);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        // Verify that the dogma repo is now encrypted.
        final Repository encryptedDogmaRepo =
                dogma.projectManager().get(projectName).repos().get(repoName);
        assertThat(encryptedDogmaRepo.isEncrypted()).isTrue();
    }

    @Test
    void migrateNonDogmaRepoInDogmaProjectShouldFail() {
        final String projectName = InternalProjectInitializer.INTERNAL_PROJECT_DOGMA;

        // The meta repository is no longer created, so attempting to migrate it should return 404.
        final AggregatedHttpResponse response = migrateRepository(dogma.httpClient(),
                                                                  projectName, Project.REPO_META);
        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static AggregatedHttpResponse migrateRepository(WebClient client,
                                                             String projectName, String repoName) {
        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.POST,
                "/api/v1/projects/" + projectName + "/repos/" + repoName + "/migrate/encrypted",
                HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        return client.execute(headers).aggregate().join();
    }
}
