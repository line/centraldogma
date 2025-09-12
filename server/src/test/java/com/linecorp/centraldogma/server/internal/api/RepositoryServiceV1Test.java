/*
 * Copyright 2020 LINE Corporation
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

import static com.linecorp.centraldogma.internal.Util.USER_EMAIL_SUFFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.REPOS;
import static com.linecorp.centraldogma.server.metadata.RepositoryMetadata.DEFAULT_PROJECT_ROLES;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.InvalidHttpResponseException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.ReadOnlyException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.RepositoryStatus;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.PushResultDto;
import com.linecorp.centraldogma.internal.api.v1.RepositoryDto;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.credential.CreateCredentialRequest;
import com.linecorp.centraldogma.server.internal.api.MetadataApiService.IdAndProjectRole;
import com.linecorp.centraldogma.server.internal.credential.AccessTokenCredential;
import com.linecorp.centraldogma.server.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.metadata.Roles;
import com.linecorp.centraldogma.server.metadata.Token;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class RepositoryServiceV1Test {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.systemAdministrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }
    };

    private static final String REPOS_PREFIX = PROJECTS_PREFIX + "/myPro" + REPOS;

    private static WebClient systemAdminClient;
    private static WebClient userClient;
    private static WebClient tokenClient;

    @BeforeAll
    static void setUp() throws JsonProcessingException, UnknownHostException {
        final URI uri = dogma.httpClient().uri();
        systemAdminClient = WebClient.builder(uri)
                                     .auth(AuthToken.ofOAuth2(getAccessToken(dogma.httpClient(),
                                                                             TestAuthMessageUtil.USERNAME,
                                                                             TestAuthMessageUtil.PASSWORD)))
                                     .build();
        createProject(systemAdminClient);
        userClient = WebClient.builder(uri)
                              .auth(AuthToken.ofOAuth2(getAccessToken(dogma.httpClient(),
                                                                      TestAuthMessageUtil.USERNAME2,
                                                                      TestAuthMessageUtil.PASSWORD2)))
                              .build();
        final QueryParams params = QueryParams.builder()
                                              .add("appId", "appId1")
                                              .add("isSystemAdmin", "false")
                                              .build();
        final String appToken = userClient.blocking().prepare()
                                          .post("/api/v1/tokens")
                                          .content(MediaType.FORM_DATA, params.toQueryString())
                                          .asJson(Token.class, new ObjectMapper()).execute().content().secret();
        tokenClient = WebClient.builder(uri)
                               .auth(AuthToken.ofOAuth2(appToken))
                               .build();
    }

    @Test
    void createRepository() throws IOException {
        // Add a user (foo2) as a member of the project.
        HttpRequest request = HttpRequest.builder()
                                         .post("/api/v1/metadata/myPro/members")
                                         .contentJson(new IdAndProjectRole(
                                                 TestAuthMessageUtil.USERNAME2, ProjectRole.MEMBER))
                                         .build();
        assertThat(systemAdminClient.execute(request).aggregate().join().status()).isSameAs(HttpStatus.OK);
        // Add a token as a member of the project.
        request = HttpRequest.builder()
                             .post("/api/v1/metadata/myPro/tokens")
                             .contentJson(new IdAndProjectRole("appId1", ProjectRole.MEMBER))
                             .build();
        assertThat(systemAdminClient.execute(request).aggregate().join().status()).isSameAs(HttpStatus.OK);

        createRepositoryAndValidate(userClient, "myRepo");
        createRepositoryAndValidate(tokenClient, "myRepo2");

        final ProjectMetadata projectMetadata =
                systemAdminClient.blocking().prepare().get(PROJECTS_PREFIX + "/myPro")
                                 .asJson(ProjectMetadata.class, new ObjectMapper())
                                 .execute()
                                 .content();
        assertThat(projectMetadata.repo("myRepo").roles()).isEqualTo(
                new Roles(DEFAULT_PROJECT_ROLES,
                          ImmutableMap.of(TestAuthMessageUtil.USERNAME2 + USER_EMAIL_SUFFIX,
                                          RepositoryRole.ADMIN),
                          ImmutableMap.of()));
        assertThat(projectMetadata.repo("myRepo2").roles()).isEqualTo(
                new Roles(DEFAULT_PROJECT_ROLES,
                          ImmutableMap.of(),
                          ImmutableMap.of("appId1", RepositoryRole.ADMIN)));
    }

    private static AggregatedHttpResponse createRepository(WebClient client, String repoName) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, REPOS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final String body = "{\"name\": \"" + repoName + "\"}";

        return client.execute(headers, body).aggregate().join();
    }

    private static void createRepositoryAndValidate(
            WebClient userClient1, String repoName) throws JsonParseException {
        final AggregatedHttpResponse aRes = createRepository(userClient1, repoName);
        final ResponseHeaders headers = ResponseHeaders.of(aRes.headers());
        assertThat(headers.status()).isEqualTo(HttpStatus.CREATED);

        final String location = headers.get(HttpHeaderNames.LOCATION);
        assertThat(location).isEqualTo("/api/v1/projects/myPro/repos/" + repoName);

        final JsonNode jsonNode = Jackson.readTree(aRes.contentUtf8());
        assertThat(jsonNode.get("name").asText()).isEqualTo(repoName);
        assertThat(jsonNode.get("headRevision").asInt()).isOne();
        assertThat(jsonNode.get("createdAt").asText()).isNotNull();
    }

    @Test
    void createRepositoryWithSameName() {
        createRepository(systemAdminClient, "myNewRepo");

        // create again with the same name
        final AggregatedHttpResponse aRes = createRepository(systemAdminClient, "myNewRepo");
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.CONFLICT);
        final String expectedJson =
                '{' +
                "  \"exception\": \"" + RepositoryExistsException.class.getName() + "\"," +
                "  \"message\": \"repository already exists: myPro/myNewRepo\"" +
                '}';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    void createRepositoryWithInvalidName() {
        final AggregatedHttpResponse aRes = createRepository(systemAdminClient, "myRepo.git");
        assertThat(aRes.headers().status()).isSameAs(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createRepositoryInAbsentProject() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST,
                                                         PROJECTS_PREFIX + "/absentProject" + REPOS,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final String body = "{\"name\": \"myRepo\"}";
        final AggregatedHttpResponse aRes = systemAdminClient.execute(headers, body).aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
        final String expectedJson =
                '{' +
                "  \"exception\": \"" + ProjectNotFoundException.class.getName() + "\"," +
                "  \"message\": \"project not found: absentProject\"" +
                '}';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    void removeRepository() {
        createRepository(systemAdminClient, "foo");
        final AggregatedHttpResponse aRes = systemAdminClient.delete(REPOS_PREFIX + "/foo").aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void removeAbsentRepository() {
        final AggregatedHttpResponse aRes = systemAdminClient.delete(REPOS_PREFIX + "/foo").aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removeMetaRepository() {
        final AggregatedHttpResponse aRes = systemAdminClient.delete(REPOS_PREFIX + '/' + Project.REPO_META)
                                                             .aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unremoveAbsentRepository() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH, REPOS_PREFIX + "/baz",
                                                         HttpHeaderNames.CONTENT_TYPE,
                                                         "application/json-patch+json");

        final String unremovePatch = "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]";
        final AggregatedHttpResponse aRes =
                systemAdminClient.execute(headers, unremovePatch).aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateRepositoryStatus() throws UnknownHostException {
        final String repoName = "statusRepo";
        final AggregatedHttpResponse aRes = createRepository(systemAdminClient, repoName);
        assertThat(aRes.status()).isEqualTo(HttpStatus.CREATED);
        final BlockingWebClient client = systemAdminClient.blocking();
        ResponseEntity<RepositoryDto> responseEntity = client.prepare()
                                                             .get(REPOS_PREFIX + '/' + repoName)
                                                             .asJson(RepositoryDto.class)
                                                             .execute();
        assertThat(responseEntity.status()).isSameAs(HttpStatus.OK);
        assertThat(responseEntity.content().status()).isSameAs(RepositoryStatus.ACTIVE);

        responseEntity = updateStatus(RepositoryStatus.READ_ONLY, repoName);
        assertThat(responseEntity.status()).isSameAs(HttpStatus.OK);
        assertThat(responseEntity.content().status()).isSameAs(RepositoryStatus.READ_ONLY);

        final CentralDogma dogmaClient = new ArmeriaCentralDogmaBuilder()
                .host(dogma.serverAddress().getHostString(), dogma.serverAddress().getPort())
                .accessToken(getAccessToken(dogma.httpClient(),
                                            TestAuthMessageUtil.USERNAME,
                                            TestAuthMessageUtil.PASSWORD))
                .build();

        final CentralDogmaRepository centralDogmaRepository = dogmaClient.forRepo("myPro", "statusRepo");
        assertThatThrownBy(() -> centralDogmaRepository.commit("commit", Change.ofTextUpsert("/foo.txt", "foo"))
                                                       .push().join())
                .hasCauseExactlyInstanceOf(ReadOnlyException.class);

        responseEntity = updateStatus(RepositoryStatus.ACTIVE, repoName);
        assertThat(responseEntity.status()).isSameAs(HttpStatus.OK);
        assertThat(responseEntity.content().status()).isSameAs(RepositoryStatus.ACTIVE);
        final PushResult result =
                centralDogmaRepository.commit("commit", Change.ofTextUpsert("/foo.txt", "foo")).push().join();
        assertThat(result.revision()).isEqualTo(new Revision(2));
    }

    @Test
    void updateInternalRepositoryStatus() {
        ResponseEntity<RepositoryDto> statusResponseEntity =
                updateStatus(RepositoryStatus.READ_ONLY, Project.REPO_META);
        assertThat(statusResponseEntity.status()).isSameAs(HttpStatus.OK);
        assertThat(statusResponseEntity.content().status()).isSameAs(RepositoryStatus.READ_ONLY);

        assertThatThrownBy(() -> createCredential()).isInstanceOf(InvalidHttpResponseException.class)
                                                    // Read only exception produces 503
                                                    .hasMessageContaining("status: 503 Service Unavailable");

        statusResponseEntity = updateStatus(RepositoryStatus.ACTIVE, Project.REPO_META);
        assertThat(statusResponseEntity.status()).isSameAs(HttpStatus.OK);
        assertThat(statusResponseEntity.content().status()).isSameAs(RepositoryStatus.ACTIVE);

        final ResponseEntity<PushResultDto> credential = createCredential();
        assertThat(credential.status()).isSameAs(HttpStatus.CREATED);
    }

    private static ResponseEntity<RepositoryDto> updateStatus(RepositoryStatus status, String repoName) {
        final BlockingWebClient client = systemAdminClient.blocking();
        return client.prepare()
                     .put(REPOS_PREFIX + '/' + repoName + "/status")
                     .contentJson(new UpdateRepositoryStatusRequest(status))
                     .asJson(RepositoryDto.class)
                     .execute();
    }

    private static ResponseEntity<PushResultDto> createCredential() {
        final CreateCredentialRequest request = new CreateCredentialRequest(
                "access-token-credential", new AccessTokenCredential(null, "secret-token-abc-1"));
        return systemAdminClient.blocking().prepare()
                                .post("/api/v1/projects/myPro/credentials")
                                .contentJson(request)
                                .asJson(PushResultDto.class)
                                .execute();
    }

    @Nested
    class RepositoriesTest {

        @RegisterExtension
        final CentralDogmaExtension dogma = new CentralDogmaExtension() {
            @Override
            protected void configureHttpClient(WebClientBuilder builder) {
                builder.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
            }

            @Override
            protected boolean runForEachTest() {
                return true;
            }
        };

        @BeforeEach
        void setUp() {
            createProject(dogma.httpClient());
        }

        @Test
        void listRepositories() {
            final WebClient client = dogma.httpClient();
            createRepository(client, "myRepo");
            final AggregatedHttpResponse aRes = client.get(REPOS_PREFIX).aggregate().join();

            assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.OK);
            final String expectedJson =
                    '[' +
                    "   {" +
                    "       \"name\": \"dogma\"," +
                    "       \"creator\": {" +
                    "           \"name\": \"system\"," +
                    "           \"email\": \"system@localhost.localdomain\"" +
                    "       }," +
                    "       \"headRevision\": \"${json-unit.ignore}\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/dogma\"," +
                    "       \"createdAt\": \"${json-unit.ignore}\"," +
                    "       \"status\": \"ACTIVE\"" +
                    "   }," +
                    "   {" +
                    "       \"name\": \"meta\"," +
                    "       \"creator\": {" +
                    "           \"name\": \"system\"," +
                    "           \"email\": \"system@localhost.localdomain\"" +
                    "       }," +
                    "       \"headRevision\": \"${json-unit.ignore}\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/meta\"," +
                    "       \"createdAt\": \"${json-unit.ignore}\"," +
                    "       \"status\": \"ACTIVE\"" +
                    "   }," +
                    "   {" +
                    "       \"name\": \"myRepo\"," +
                    "       \"creator\": {" +
                    "           \"name\": \"admin\"," +
                    "           \"email\": \"admin@localhost.localdomain\"" +
                    "       }," +
                    "       \"headRevision\": \"${json-unit.ignore}\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo\"," +
                    "       \"createdAt\": \"${json-unit.ignore}\"," +
                    "       \"status\": \"ACTIVE\"" +
                    "   }" +
                    ']';
            assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
        }

        @Test
        void listRemovedRepositories() throws IOException {
            final WebClient client = dogma.httpClient();
            createRepository(client, "trustin");
            createRepository(client, "hyangtack");
            createRepository(client, "minwoox");
            client.delete(REPOS_PREFIX + "/hyangtack").aggregate().join();
            client.delete(REPOS_PREFIX + "/minwoox").aggregate().join();

            final AggregatedHttpResponse removedRes = client.get(REPOS_PREFIX + "?status=removed")
                                                            .aggregate().join();
            assertThat(ResponseHeaders.of(removedRes.headers()).status()).isEqualTo(HttpStatus.OK);
            final String expectedJson =
                    '[' +
                    "   {" +
                    "       \"name\": \"hyangtack\"" +
                    "   }," +
                    "   {" +
                    "       \"name\": \"minwoox\"" +
                    "   }" +
                    ']';
            assertThatJson(removedRes.contentUtf8()).isEqualTo(expectedJson);

            final AggregatedHttpResponse remainedRes = client.get(REPOS_PREFIX).aggregate().join();
            final String remains = remainedRes.contentUtf8();
            final JsonNode jsonNode = Jackson.readTree(remains);

            // dogma, meta and trustin repositories are left
            assertThat(jsonNode).hasSize(3);
        }

        @Test
        void unremoveRepository() {
            final WebClient client = dogma.httpClient();
            createRepository(client, "foo");
            client.delete(REPOS_PREFIX + "/foo").aggregate().join();
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH, REPOS_PREFIX + "/foo",
                                                             HttpHeaderNames.CONTENT_TYPE,
                                                             "application/json-patch+json");

            final String unremovePatch = "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]";
            final AggregatedHttpResponse aRes = client.execute(headers, unremovePatch).aggregate().join();
            assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.OK);
            final String expectedJson =
                    '{' +
                    "   \"name\": \"foo\"," +
                    "   \"creator\": {" +
                    "       \"name\": \"admin\"," +
                    "       \"email\": \"admin@localhost.localdomain\"" +
                    "   }," +
                    "   \"headRevision\": 1," +
                    "   \"url\": \"/api/v1/projects/myPro/repos/foo\"," +
                    "   \"createdAt\": \"${json-unit.ignore}\"," +
                    "   \"status\": \"ACTIVE\"" +
                    '}';
            assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
        }

        @Test
        void normalizeRevision() {
            final WebClient client = dogma.httpClient();
            createRepository(client, "foo");
            final AggregatedHttpResponse res = client.get(REPOS_PREFIX + "/foo/revision/-1")
                                                     .aggregate().join();
            assertThatJson(res.contentUtf8()).isEqualTo("{\"revision\":1}");
        }
    }

    private static void createProject(WebClient client) {
        // the default project used for unit tests
        final String body = "{\"name\": \"myPro\"}";
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, PROJECTS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);

        client.execute(headers, body).aggregate().join();
    }
}
