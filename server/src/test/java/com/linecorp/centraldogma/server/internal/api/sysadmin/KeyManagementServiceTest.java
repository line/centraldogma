/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.api.sysadmin;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.REPOS;
import static com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil.getSessionKeyVersion;
import static com.linecorp.centraldogma.server.internal.storage.MigratingMetaToDogmaRepositoryService.META_TO_DOGMA_MIGRATION_JOB;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getSessionCookie;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.login;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;

import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.type.TypeReference;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.EncryptionAtRestConfig;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.EncryptionGitStorage;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.GitObjectMetadata;
import com.linecorp.centraldogma.server.internal.storage.repository.git.rocksdb.RocksDbRepository;
import com.linecorp.centraldogma.server.storage.encryption.WrappedDekDetails;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class KeyManagementServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.encryptionAtRest(new EncryptionAtRestConfig(true, true, "kekId"))
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
            // Commit the META_TO_DOGMA_MIGRATION_JOB file to the dogma/dogma repository so that
            // a meta repository is not created when creating a project.
            // This will be removed once meta repository migration is done.
            final Project project = projectManager().get(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA);
            project.repos().get(Project.REPO_DOGMA).commit(
                    Revision.HEAD, 0, Author.SYSTEM, "Add",
                    Change.ofJsonUpsert(META_TO_DOGMA_MIGRATION_JOB, "{ \"a\": \"b\" }")).join();
            client.createProject("foo").join();
            client.createRepository("foo", "bar") // non-encrypted repo
                  .join();
        }
    };

    @BeforeAll
    static void setUp() {
        createRepository(dogma.httpClient(), "encryptedRepo");
    }

    private static AggregatedHttpResponse createRepository(WebClient client, String repoName) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, PROJECTS_PREFIX + "/foo" + REPOS,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final String body = "{\"name\": \"" + repoName + "\"," +
                            " \"encrypt\": true}";

        return client.execute(headers, body).aggregate().join();
    }

    @Test
    void rotateWdek() throws Exception {
        final ResponseEntity<List<WrappedDekDetails>> response =
                dogma.httpClient().blocking().prepare().get(API_V1_PATH_PREFIX + "/wdeks")
                     .asJson(new TypeReference<List<WrappedDekDetails>>() {})
                     .execute();
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.content().stream().map(WrappedDek::of)).containsExactlyInAnyOrder(
                new WrappedDek("foo", "dogma", 1, "kekId"),
                new WrappedDek("foo", "encryptedRepo", 1, "kekId"));

        // Add a commit and verify it uses WDEK version 1
        final PushResult res1 = dogma.client().forRepo("foo", "encryptedRepo")
                                     .commit("Add file", Change.ofJsonUpsert("/a.json", "{ \"foo\": \"bar\" }"))
                                     .push().join();
        assertThat(res1.revision().major()).isEqualTo(2);

        // Check that the content is encrypted using WDEK version 1
        final int dekVersion1 = getCommitDekVersion("foo", "encryptedRepo", res1.revision());
        assertThat(dekVersion1).isEqualTo(1);

        // Rotate the WDEK
        final AggregatedHttpResponse rotateResponse =
                dogma.httpClient().execute(RequestHeaders.of(HttpMethod.POST,
                        API_V1_PATH_PREFIX + "/projects/foo/repos/encryptedRepo/wdeks/rotate"))
                     .aggregate().join();
        assertThat(rotateResponse.status()).isSameAs(HttpStatus.NO_CONTENT);

        // Verify the WDEK list now contains version 2
        final ResponseEntity<List<WrappedDekDetails>> response2 =
                dogma.httpClient().blocking().prepare().get(API_V1_PATH_PREFIX + "/wdeks")
                     .asJson(new TypeReference<List<WrappedDekDetails>>() {})
                     .execute();
        assertThat(response2.status()).isSameAs(HttpStatus.OK);
        assertThat(response2.content().stream()
                            .filter(w -> "foo".equals(w.projectName()) &&
                                        "encryptedRepo".equals(w.repoName()))
                            .map(WrappedDek::of))
                .containsExactlyInAnyOrder(
                        new WrappedDek("foo", "encryptedRepo", 1, "kekId"),
                        new WrappedDek("foo", "encryptedRepo", 2, "kekId"));

        // Add another commit and verify it uses WDEK version 2
        final PushResult res2 = dogma.client().forRepo("foo", "encryptedRepo")
                                     .commit("Add another file",
                                            Change.ofJsonUpsert("/b.json", "{ \"bar\": \"baz\" }"))
                                     .push().join();
        assertThat(res2.revision().major()).isEqualTo(3);

        // Check that the new content is encrypted using WDEK version 2
        final int dekVersion2 = getCommitDekVersion("foo", "encryptedRepo", res2.revision());
        assertThat(dekVersion2).isEqualTo(2);
    }

    @Test
    void rotateSessionMasterKey() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

        // Get the initial session master key details
        final ResponseEntity<SessionMasterKeyDto> response1 =
                dogma.httpClient().blocking().prepare().get(API_V1_PATH_PREFIX + "/masterkeys/session")
                     .asJson(SessionMasterKeyDto.class)
                     .execute();
        assertThat(response1.status()).isSameAs(HttpStatus.OK);
        final SessionMasterKeyDto initialDetails = response1.content();
        assertThat(initialDetails.version()).isEqualTo(1);
        assertThat(initialDetails.kekId()).isEqualTo("kekId");
        assertThat(initialDetails.creation()).isNotNull();

        // Login to create a session cookie with the initial key version
        final AggregatedHttpResponse loginRes1 = login(dogma.httpClient(), USERNAME, PASSWORD);
        assertThat(loginRes1.status()).isSameAs(HttpStatus.OK);
        final Cookie sessionCookie1 = getSessionCookie(loginRes1, false, true);

        // Verify the session cookie uses key version 1
        final int keyVersion1 = getSessionKeyVersion(ctx, sessionCookie1.value());
        assertThat(keyVersion1).isEqualTo(1);

        // Rotate the session master key
        final AggregatedHttpResponse rotateResponse =
                dogma.httpClient().execute(RequestHeaders.of(HttpMethod.POST,
                        API_V1_PATH_PREFIX + "/masterkeys/session/rotate"))
                     .aggregate().join();
        assertThat(rotateResponse.status()).isSameAs(HttpStatus.NO_CONTENT);

        // Get the session master key details after rotation
        final ResponseEntity<SessionMasterKeyDto> response2 =
                dogma.httpClient().blocking().prepare().get(API_V1_PATH_PREFIX + "/masterkeys/session")
                     .asJson(SessionMasterKeyDto.class)
                     .execute();
        assertThat(response2.status()).isSameAs(HttpStatus.OK);
        final SessionMasterKeyDto rotatedDetails = response2.content();

        // Verify the version has been incremented
        assertThat(rotatedDetails.version()).isEqualTo(2);
        assertThat(rotatedDetails.kekId()).isEqualTo("kekId");
        assertThat(rotatedDetails.creation()).isNotNull();

        // The creation timestamp should be different (later) for the new key
        assertThat(rotatedDetails.creation()).isNotEqualTo(initialDetails.creation());

        // Login again to create a new session cookie with the rotated key version
        final AggregatedHttpResponse loginRes2 = login(dogma.httpClient(), USERNAME, PASSWORD);
        assertThat(loginRes2.status()).isSameAs(HttpStatus.OK);
        final Cookie sessionCookie2 = getSessionCookie(loginRes2, false, true);

        // Verify the new session cookie uses key version 2
        final int keyVersion2 = getSessionKeyVersion(ctx, sessionCookie2.value());
        assertThat(keyVersion2).isEqualTo(2);

        // Rotate again to verify multiple rotations work
        final AggregatedHttpResponse rotateResponse2 =
                dogma.httpClient().execute(RequestHeaders.of(HttpMethod.POST,
                        API_V1_PATH_PREFIX + "/masterkeys/session/rotate"))
                     .aggregate().join();
        assertThat(rotateResponse2.status()).isSameAs(HttpStatus.NO_CONTENT);

        // Verify version 3
        final ResponseEntity<SessionMasterKeyDto> response3 =
                dogma.httpClient().blocking().prepare().get(API_V1_PATH_PREFIX + "/masterkeys/session")
                     .asJson(SessionMasterKeyDto.class)
                     .execute();
        assertThat(response3.status()).isSameAs(HttpStatus.OK);
        assertThat(response3.content().version()).isEqualTo(3);
        assertThat(response3.content().kekId()).isEqualTo("kekId");

        // Login once more to verify the third key version
        final AggregatedHttpResponse loginRes3 = login(dogma.httpClient(), USERNAME, PASSWORD);
        assertThat(loginRes3.status()).isSameAs(HttpStatus.OK);
        final Cookie sessionCookie3 = getSessionCookie(loginRes3, false, true);

        // Verify the new session cookie uses key version 3
        final int keyVersion3 = getSessionKeyVersion(ctx, sessionCookie3.value());
        assertThat(keyVersion3).isEqualTo(3);
    }

    private static int getCommitDekVersion(String projectName, String repoName, Revision revision)
            throws Exception {
        final Project project = dogma.projectManager().get(projectName);
        final com.linecorp.centraldogma.server.storage.repository.Repository repo =
                project.repos().get(repoName);
        final org.eclipse.jgit.lib.Repository jgitRepo = repo.jGitRepository();
        assertThat(jgitRepo).isInstanceOf(RocksDbRepository.class);

        final RocksDbRepository rocksDbRepo = (RocksDbRepository) jgitRepo;
        final EncryptionGitStorage encryptionGitStorage = rocksDbRepo.encryptionGitStorage();

        // Get the commit object ID for this revision
        final ObjectId commitObjectId = encryptionGitStorage.getRevisionObjectId(revision);

        // Get the metadata key for this commit object
        final byte[] metadataKey = encryptionGitStorage.objectMetadataKey(commitObjectId);

        // Retrieve the metadata
        final byte[] metadataBytes = dogma.encryptionStorageManager().getMetadata(metadataKey);
        assertThat(metadataBytes).isNotNull();

        // Parse the metadata to get the DEK version
        final GitObjectMetadata metadata = GitObjectMetadata.fromBytes(metadataBytes);

        return metadata.keyVersion();
    }

    private static final class WrappedDek {

        static WrappedDek of(WrappedDekDetails dekDetails) {
            return new WrappedDek(dekDetails.projectName(), dekDetails.repoName(), dekDetails.dekVersion(),
                                  dekDetails.kekId());
        }

        private final String pro;
        private final String repoName;
        private final int dekVersion;
        private final String kekId;

        WrappedDek(String pro, String repoName, int dekVersion, String kekId) {
            this.pro = pro;
            this.repoName = repoName;
            this.dekVersion = dekVersion;
            this.kekId = kekId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(pro, repoName, dekVersion, kekId);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final WrappedDek other = (WrappedDek) obj;
            return pro.equals(other.pro) &&
                   repoName.equals(other.repoName) &&
                   dekVersion == other.dekVersion &&
                   kekId.equals(other.kekId);
        }
    }
}
