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

import java.io.File;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.EncryptionConfig;
import com.linecorp.centraldogma.server.GracefulShutdownTimeout;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;

class FallbackFromReadOnlyTest {

    private static final String PROJECT_NAME = "myProject";
    private static final String REPO_NAME = "myRepo";

    @TempDir
    File dataDir;

    @Test
    void fallbackFromReadOnlyState() throws Exception {
        // 1. Start the server WITHOUT encryption and create a project/repo.
        CentralDogma dogma = new CentralDogmaBuilder(dataDir)
                .port(0, SessionProtocol.HTTP)
                .webAppEnabled(false)
                .gracefulShutdownTimeout(new GracefulShutdownTimeout(0, 0))
                .systemAdministrators(USERNAME)
                .authProviderFactory(new TestAuthProviderFactory())
                .build();
        dogma.start().join();

        InetSocketAddress addr = dogma.activePort().localAddress();
        WebClient client = newClient(addr);

        createProject(client, PROJECT_NAME);
        createRepository(client, PROJECT_NAME, REPO_NAME);

        assertThat(dogma.projectManager().get(PROJECT_NAME).repos().get(REPO_NAME).isEncrypted()).isFalse();
        dogma.stop().join();

        // 2. Restart the server WITH encryption enabled and migrate the repo.
        dogma = new CentralDogmaBuilder(dataDir)
                .port(0, SessionProtocol.HTTP)
                .webAppEnabled(false)
                .gracefulShutdownTimeout(new GracefulShutdownTimeout(0, 0))
                .encryption(new EncryptionConfig(true, false, "kekId"))
                .systemAdministrators(USERNAME)
                .authProviderFactory(new TestAuthProviderFactory())
                .build();
        dogma.start().join();

        addr = dogma.activePort().localAddress();
        client = newClient(addr);

        // Migrate the non-encrypted repo to an encrypted repo.
        AggregatedHttpResponse response = postApi(client, PROJECT_NAME, REPO_NAME, "/migrate/encrypted");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(dogma.projectManager().get(PROJECT_NAME).repos().get(REPO_NAME).isEncrypted()).isTrue();

        // Set the repository to read-only.
        response = updateRepositoryStatus(client, PROJECT_NAME, REPO_NAME, "READ_ONLY");
        assertThat(response.contentUtf8()).contains("READ_ONLY");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        // Fallback should succeed even in read-only state.
        response = postApi(client, PROJECT_NAME, REPO_NAME, "/migrate/file");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).contains("\"status\":\"WRITABLE\"");

        assertThat(dogma.projectManager().get(PROJECT_NAME).repos().get(REPO_NAME).isEncrypted()).isFalse();
        dogma.stop().join();
    }

    private static int appIdCounter;

    private static WebClient newClient(InetSocketAddress addr) {
        final String appId = "testAppId" + appIdCounter++;
        final String accessToken = getAccessToken(
                WebClient.of("http://127.0.0.1:" + addr.getPort()),
                USERNAME, PASSWORD, appId, true);
        return WebClient.builder("h2c://127.0.0.1:" + addr.getPort())
                        .auth(AuthToken.ofOAuth2(accessToken))
                        .build();
    }

    private static void createProject(WebClient client, String projectName) {
        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.POST, "/api/v1/projects",
                HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final AggregatedHttpResponse response =
                client.execute(headers, "{\"name\":\"" + projectName + "\"}").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
    }

    private static void createRepository(WebClient client, String projectName, String repoName) {
        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.POST, "/api/v1/projects/" + projectName + "/repos",
                HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final AggregatedHttpResponse response =
                client.execute(headers, "{\"name\":\"" + repoName + "\"}").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
    }

    private static AggregatedHttpResponse postApi(WebClient client,
                                                   String projectName, String repoName, String action) {
        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.POST,
                "/api/v1/projects/" + projectName + "/repos/" + repoName + action,
                HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        return client.execute(headers).aggregate().join();
    }

    private static AggregatedHttpResponse updateRepositoryStatus(WebClient client, String projectName,
                                                                  String repoName, String status) {
        final RequestHeaders headers = RequestHeaders.of(
                HttpMethod.PUT,
                "/api/v1/projects/" + projectName + "/repos/" + repoName + "/status",
                HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        return client.execute(headers, "{\"status\":\"" + status + "\"}").aggregate().join();
    }
}
