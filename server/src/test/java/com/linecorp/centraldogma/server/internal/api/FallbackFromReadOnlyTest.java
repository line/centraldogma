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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.RepositoryStatus;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.EncryptionConfig;
import com.linecorp.centraldogma.server.GracefulShutdownTimeout;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.metadata.RepositoryMetadata;
import com.linecorp.centraldogma.server.storage.encryption.EncryptionStorageManager;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;
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

        createPreservingMirror(dogma, PROJECT_NAME, REPO_NAME);
        final CentralDogma encryptedDogma = dogma;
        assertThat(encryptedDogma.projectManager().get(PROJECT_NAME).metaRepo()
                                  .mirrors(REPO_NAME, true).join())
                .isEmpty();
        assertThatThrownBy(() -> encryptedDogma.projectManager().get(PROJECT_NAME).metaRepo()
                                               .mirror(REPO_NAME, "history").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "preserveRemoteCommitHistory is not supported for the encrypted repository");

        // Set the repository to read-only.
        response = updateRepositoryStatus(client, PROJECT_NAME, REPO_NAME, "READ_ONLY");
        assertThat(response.contentUtf8()).contains("READ_ONLY");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        // Fallback should succeed even in read-only state.
        response = postApi(client, PROJECT_NAME, REPO_NAME, "/migrate/file");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).contains("\"status\":\"ACTIVE\"");

        assertThat(dogma.projectManager().get(PROJECT_NAME).repos().get(REPO_NAME).isEncrypted()).isFalse();
        dogma.stop().join();
    }

    @Test
    void preservingMirrorPreventsEncryptionMigration() throws Exception {
        CentralDogma dogma = new CentralDogmaBuilder(dataDir)
                .port(0, SessionProtocol.HTTP)
                .webAppEnabled(false)
                .gracefulShutdownTimeout(new GracefulShutdownTimeout(0, 0))
                .systemAdministrators(USERNAME)
                .authProviderFactory(new TestAuthProviderFactory())
                .build();
        dogma.start().join();
        WebClient client = newClient(dogma.activePort().localAddress());
        createProject(client, PROJECT_NAME);
        createRepository(client, PROJECT_NAME, REPO_NAME);
        createPreservingMirror(dogma, PROJECT_NAME, REPO_NAME);
        dogma.stop().join();

        dogma = new CentralDogmaBuilder(dataDir)
                .port(0, SessionProtocol.HTTP)
                .webAppEnabled(false)
                .gracefulShutdownTimeout(new GracefulShutdownTimeout(0, 0))
                .encryption(new EncryptionConfig(true, false, "kekId"))
                .systemAdministrators(USERNAME)
                .authProviderFactory(new TestAuthProviderFactory())
                .build();
        dogma.start().join();

        try {
            client = newClient(dogma.activePort().localAddress());

            final AggregatedHttpResponse response =
                    postApi(client, PROJECT_NAME, REPO_NAME, "/migrate/encrypted");
            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.contentUtf8())
                    .contains("Cannot encrypt a repository with a mirror that preserves remote commit history");
            assertThat(dogma.projectManager().get(PROJECT_NAME).repos().get(REPO_NAME).isEncrypted())
                    .isFalse();
            assertThat(dogma.projectManager().get(PROJECT_NAME).metadata().repo(REPO_NAME).status())
                    .isEqualTo(RepositoryStatus.ACTIVE);
        } finally {
            dogma.stop().join();
        }
    }

    @Test
    void preservingMirrorCreatedDuringMigrationRestoresActiveStatus() throws Exception {
        final CommandExecutor executor = mock(CommandExecutor.class);
        final MetadataService metadataService = mock(MetadataService.class);
        final EncryptionStorageManager encryptionStorageManager = mock(EncryptionStorageManager.class);
        final Project project = mock(Project.class);
        final Repository repository = mock(Repository.class);
        final MetaRepository metaRepository = mock(MetaRepository.class);
        final ProjectMetadata projectMetadata = mock(ProjectMetadata.class);
        final RepositoryMetadata repositoryMetadata = mock(RepositoryMetadata.class);
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        final String mirrorPath = "/repos/" + REPO_NAME + "/mirrors/history.json";
        final String mirrorPattern = "/repos/" + REPO_NAME + "/mirrors/*.json";
        final Map<String, Entry<?>> noMirrors = Map.of();
        final Map<String, Entry<?>> preservingMirror = Map.of(
                mirrorPath, Entry.ofJson(Revision.INIT, mirrorPath,
                                         "{\"preserveRemoteCommitHistory\":true}"));

        when(project.name()).thenReturn(PROJECT_NAME);
        when(project.metadata()).thenReturn(projectMetadata);
        when(project.metaRepo()).thenReturn(metaRepository);
        when(projectMetadata.repos()).thenReturn(Map.of(REPO_NAME, repositoryMetadata));
        when(repositoryMetadata.status()).thenReturn(RepositoryStatus.ACTIVE);
        when(repository.name()).thenReturn(REPO_NAME);
        when(repository.parent()).thenReturn(project);
        when(repository.normalizeNow(Revision.HEAD)).thenReturn(Revision.INIT);
        when(repository.isEncrypted()).thenReturn(false);
        when(metaRepository.find(Revision.HEAD, mirrorPattern))
                .thenReturn(CompletableFuture.completedFuture(noMirrors))
                .thenReturn(CompletableFuture.completedFuture(preservingMirror));
        when(encryptionStorageManager.enabled()).thenReturn(true);
        when(encryptionStorageManager.generateWdek())
                .thenReturn(CompletableFuture.completedFuture("wrapped-dek"));
        when(encryptionStorageManager.kekId()).thenReturn("kek-id");
        when(metadataService.updateRepositoryStatus(
                eq(Author.SYSTEM), eq(PROJECT_NAME), eq(REPO_NAME), any(RepositoryStatus.class)))
                .thenReturn(CompletableFuture.completedFuture(new Revision(2)),
                            CompletableFuture.completedFuture(new Revision(3)));

        final RepositoryServiceV1 service =
                new RepositoryServiceV1(executor, metadataService, encryptionStorageManager);
        assertThatThrownBy(() -> service.migrateToEncryptedRepository(
                ctx, project, repository, Author.SYSTEM).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Cannot encrypt a repository with a mirror that preserves remote commit history");

        final InOrder statusUpdates = inOrder(metadataService);
        statusUpdates.verify(metadataService).updateRepositoryStatus(
                Author.SYSTEM, PROJECT_NAME, REPO_NAME, RepositoryStatus.READ_ONLY);
        statusUpdates.verify(metadataService).updateRepositoryStatus(
                Author.SYSTEM, PROJECT_NAME, REPO_NAME, RepositoryStatus.ACTIVE);
        verifyNoInteractions(executor);
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

    private static void createPreservingMirror(CentralDogma dogma, String projectName, String repoName) {
        final String mirrorPath = "/repos/" + repoName + "/mirrors/history.json";
        dogma.projectManager().get(projectName).metaRepo()
             .commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM, "Add a mirror",
                     Change.ofJsonUpsert(
                             mirrorPath,
                             "{ \"id\": \"history\", \"enabled\": true," +
                             " \"direction\": \"REMOTE_TO_LOCAL\", \"localRepo\": \"" + repoName +
                             "\", \"localPath\": \"/\"," +
                             " \"remoteUri\": \"git+https://example.com/repo.git\"," +
                             " \"credentialName\": \"\", \"preserveRemoteCommitHistory\": true }"))
             .join();
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
