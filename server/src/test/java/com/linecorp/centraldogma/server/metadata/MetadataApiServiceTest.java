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
package com.linecorp.centraldogma.server.metadata;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientTlsConfig;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.SignedCertificateExtension;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.internal.jsonpatch.ReplaceMode;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.TlsConfig;
import com.linecorp.centraldogma.server.auth.MtlsConfig;
import com.linecorp.centraldogma.server.internal.api.MetadataApiService.IdAndProjectRole;
import com.linecorp.centraldogma.server.internal.api.MetadataApiService.IdAndRepositoryRole;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class MetadataApiServiceTest {

    private static final String PROJECT_NAME = "foo_proj";
    private static final String REPOSITORY_NAME = "foo_repo";

    private static final String MEMBER_ID = "member_id@linecorp.com";
    private static final String MEMBER_TOKEN_APP_ID = "foo_token";
    private static final String MEMBER_CERTIFICATE_APP_ID = "foo_cert";
    private static final String CERT_ID = "my-client";
    private static final String APP_ID = "app_id";

    @Order(1)
    @RegisterExtension
    static final SelfSignedCertificateExtension serverCert = new SelfSignedCertificateExtension();

    @Order(2)
    @RegisterExtension
    static final SelfSignedCertificateExtension ca = new SelfSignedCertificateExtension();

    @Order(3)
    @RegisterExtension
    static final SignedCertificateExtension clientCert =
            new SignedCertificateExtension("my-client", ca, false);

    @RegisterExtension
    static CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.authProviderFactory(new TestAuthProviderFactory());
            builder.port(0, SessionProtocol.HTTPS);
            builder.tls(
                    new TlsConfig(serverCert.certificateFile(), serverCert.privateKeyFile(), null, null, null));
            builder.mtlsConfig(
                    new MtlsConfig(true, ImmutableList.of(ca.certificateFile())));
            builder.systemAdministrators(USERNAME);
        }

        @Override
        protected String accessToken() {
            return getAccessToken(
                    WebClient.builder("https://127.0.0.1:" + dogma.serverAddress().getPort())
                             .factory(ClientFactory.insecure())
                             .build(),
                    USERNAME, PASSWORD, "appId1", true, true, false);
        }

        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            configureWebClient(builder);
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject(PROJECT_NAME).join();
            client.createRepository(PROJECT_NAME, REPOSITORY_NAME).join();
        }
    };

    private static void configureWebClient(WebClientBuilder builder) {
        final TlsKeyPair tlsKeyPair = TlsKeyPair.of(clientCert.privateKey(),
                                                    clientCert.certificate());
        final ClientTlsConfig tlsConfig =
                ClientTlsConfig.builder()
                               .tlsCustomizer(b -> b.trustManager(serverCert.certificate()))
                               .build();
        builder.factory(ClientFactory.builder()
                                     .tlsProvider(TlsProvider.of(tlsKeyPair),
                                                  tlsConfig)
                                     .build());
    }

    static BlockingWebClient systemAdminClient;
    static BlockingWebClient memberTokenClient;
    static BlockingWebClient memberCertClient;

    @BeforeAll
    static void setUp() throws JsonMappingException, JsonParseException {
        systemAdminClient = dogma.blockingHttpClient();

        // Create a token
        HttpRequest request = HttpRequest.builder()
                                         .post("/api/v1/appIdentities")
                                         .content(MediaType.FORM_DATA, "appId=" + APP_ID + "&type=TOKEN")
                                         .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.CREATED);

        final String memberToken = getAccessToken(dogma.httpClient(),
                                                  USERNAME,
                                                  PASSWORD, MEMBER_TOKEN_APP_ID, false, true, false);

        // Add as a member to the project
        request = HttpRequest.builder()
                             .post("/api/v1/metadata/" + PROJECT_NAME + "/appIdentities")
                             .contentJson(new IdAndProjectRole(MEMBER_TOKEN_APP_ID, ProjectRole.MEMBER))
                             .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.OK);

        memberTokenClient = WebClient.builder(dogma.httpClient().uri())
                                     .factory(ClientFactory.insecure())
                                     .auth(AuthToken.ofOAuth2(memberToken)).build()
                                     .blocking();

        final WebClientBuilder builder = WebClient.builder(dogma.httpClient().uri());
        configureWebClient(builder);
        memberCertClient = builder.build().blocking();

        // Register a certificate app identity
        final AggregatedHttpResponse response =
                dogma.httpClient().post(API_V1_PATH_PREFIX + "appIdentities",
                                        QueryParams.of("appId", MEMBER_CERTIFICATE_APP_ID,
                                                       "type", "CERTIFICATE",
                                                       "certificateId", CERT_ID,
                                                       "isSystemAdmin", false),
                                        HttpData.empty()).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.contentUtf8()).contains("\"appId\":\"" + MEMBER_CERTIFICATE_APP_ID + '"');

        // Add certificate client as a member to the project
        request = HttpRequest.builder()
                             .post("/api/v1/metadata/" + PROJECT_NAME + "/appIdentities")
                             .contentJson(new IdAndProjectRole(MEMBER_CERTIFICATE_APP_ID, ProjectRole.MEMBER))
                             .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.OK);
    }

    @Test
    void addUpdateAndRemoveProjectMember() throws JsonProcessingException {
        addProjectMember();
        final JsonPatch jsonPatch = JsonPatch.generate(Jackson.readTree("{\"role\":\"MEMBER\"}}"),
                                                       Jackson.readTree("{\"role\":\"OWNER\"}}"),
                                                       ReplaceMode.RFC6902);
        // [{"op":"replace","path":"/role","value":"OWNER"}]
        // Update the member
        final HttpRequest request =
                HttpRequest.builder()
                           .patch("/api/v1/metadata/" + PROJECT_NAME + "/members/" + MEMBER_ID)
                           .content(MediaType.JSON_PATCH, Jackson.writeValueAsString(jsonPatch))
                           .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.OK);
        removeProjectMember();
    }

    private static void addProjectMember() {
        final HttpRequest request = HttpRequest.builder()
                                               .post("/api/v1/metadata/" + PROJECT_NAME + "/members")
                                               .contentJson(new IdAndProjectRole(MEMBER_ID, ProjectRole.MEMBER))
                                               .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.OK);
    }

    private static void removeProjectMember() {
        final HttpRequest request = HttpRequest.builder()
                                               .delete("/api/v1/metadata/" + PROJECT_NAME + "/members/" +
                                                       MEMBER_ID)
                                               .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.NO_CONTENT);
        assertThat(projectMetadata().repo(REPOSITORY_NAME).roles().users().get(MEMBER_ID)).isNull();
    }

    @Test
    void addUpdateAndRemoveProjectToken() throws JsonProcessingException {
        addProjectToken();
        HttpRequest request;

        final JsonPatch jsonPatch = JsonPatch.generate(Jackson.readTree("{\"role\":\"MEMBER\"}}"),
                                                       Jackson.readTree("{\"role\":\"OWNER\"}}"),
                                                       ReplaceMode.RFC6902);
        // [{"op":"replace","path":"/role","value":"OWNER"}]
        // Update the token
        request = HttpRequest.builder()
                             .patch("/api/v1/metadata/" + PROJECT_NAME + "/appIdentities/app_id")
                             .content(MediaType.JSON_PATCH, Jackson.writeValueAsString(jsonPatch))
                             .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.OK);

        // Remove the token
        request = HttpRequest.builder()
                             .delete("/api/v1/metadata/" + PROJECT_NAME + "/appIdentities/app_id")
                             .build();
        assertThat(systemAdminClient.execute(request).status())
                .isSameAs(HttpStatus.NO_CONTENT);
    }

    private static void addProjectToken() {
        final HttpRequest request =
                HttpRequest.builder()
                           .post("/api/v1/metadata/" + PROJECT_NAME + "/appIdentities")
                           .contentJson(new IdAndProjectRole("app_id", ProjectRole.MEMBER))
                           .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.OK);
    }

    @Test
    void addUpdateAndRemoveRepositoryUser() throws JsonProcessingException {
        addProjectMember();
        final HttpRequest request =
                HttpRequest.builder()
                           .post("/api/v1/metadata/" + PROJECT_NAME + "/repos/" +
                                 REPOSITORY_NAME + "/roles/users")
                           .contentJson(new IdAndRepositoryRole(MEMBER_ID, RepositoryRole.READ))
                           .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.OK);

        assertThat(projectMetadata().repo(REPOSITORY_NAME).roles().users().get(MEMBER_ID))
                .isSameAs(RepositoryRole.READ);

        removeProjectMember();
    }

    @Test
    void addUpdateAndRemoveRepositoryToken() throws JsonProcessingException {
        addProjectToken();
        HttpRequest request = HttpRequest.builder()
                                         .post("/api/v1/metadata/" + PROJECT_NAME + "/repos/" +
                                               REPOSITORY_NAME + "/roles/appIdentities")
                                         .contentJson(new IdAndRepositoryRole(APP_ID, RepositoryRole.READ))
                                         .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.OK);

        ProjectMetadata projectMetadata = projectMetadata();
        assertThat(projectMetadata.repo(REPOSITORY_NAME).roles().appIds().get(APP_ID))
                .isSameAs(RepositoryRole.READ);

        // Remove the member
        request = HttpRequest.builder()
                             .delete("/api/v1/metadata/" + PROJECT_NAME + "/appIdentities/" + APP_ID)
                             .build();
        assertThat(systemAdminClient.execute(request).status())
                .isSameAs(HttpStatus.NO_CONTENT);
        projectMetadata = projectMetadata();
        assertThat(projectMetadata.repo(REPOSITORY_NAME).roles().appIds().get(APP_ID)).isNull();
    }

    @Test
    void grantRoleToMemberForMetaRepository() throws Exception {
        final AggregatedHttpResponse res =
                memberTokenClient.get("/api/v1/projects/" + PROJECT_NAME + "/repos/dogma/list");
        // A member isn't allowed to access the meta repository.
        assertThat(res.status()).isSameAs(HttpStatus.FORBIDDEN);
        assertThat(res.contentUtf8()).contains(
                "Repository 'foo_proj/dogma' can be accessed only by a system administrator.");

        // Can't give a READ role to the member.
        final HttpRequest request =
                HttpRequest.builder()
                           .post("/api/v1/metadata/" + PROJECT_NAME + "/repos/dogma/roles/projects")
                           .contentJson(ProjectRoles.of(RepositoryRole.READ, null))
                           .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.BAD_REQUEST);

        // The member cannot access the meta repository.
        assertThat(memberTokenClient.get(
                "/api/v1/projects/" + PROJECT_NAME + "/repos/dogma/list").status())
                .isSameAs(HttpStatus.FORBIDDEN);
    }

    @Test
    void grantRoleToMemberForMetaRepositoryWithCert() throws Exception {
        final AggregatedHttpResponse res =
                memberCertClient.get("/api/v1/projects/" + PROJECT_NAME + "/repos/dogma/list");
        // A member with certificate isn't allowed to access the meta repository.
        assertThat(res.status()).isSameAs(HttpStatus.FORBIDDEN);
        assertThat(res.contentUtf8()).contains(
                "Repository 'foo_proj/dogma' can be accessed only by a system administrator.");

        // Can't give a READ role to the member.
        final HttpRequest request =
                HttpRequest.builder()
                           .post("/api/v1/metadata/" + PROJECT_NAME + "/repos/dogma/roles/projects")
                           .contentJson(ProjectRoles.of(RepositoryRole.READ, null))
                           .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.BAD_REQUEST);

        // The member with certificate cannot access the meta repository.
        assertThat(memberCertClient.get(
                "/api/v1/projects/" + PROJECT_NAME + "/repos/dogma/list").status())
                .isSameAs(HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldNotAllowWritePermissionForGuest() {
        final AggregatedHttpResponse response =
                systemAdminClient.prepare()
                                 .post("/api/v1/metadata/{project}/repos/{repo}/roles/projects")
                                 .pathParam("project", PROJECT_NAME)
                                 .pathParam("repo", REPOSITORY_NAME)
                                 .content(MediaType.JSON,
                                          '{' +
                                          "  \"member\": \"WRITE\"," +
                                          "  \"guest\": \"WRITE\"" +
                                          '}')
                                 .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatJson(response.contentUtf8())
                .node("message")
                .isEqualTo("WRITE is not allowed for GUEST");

        final ResponseEntity<Revision> successResponse =
                systemAdminClient.prepare()
                                 .post("/api/v1/metadata/{project}/repos/{repo}/roles/projects")
                                 .pathParam("project", PROJECT_NAME)
                                 .pathParam("repo", REPOSITORY_NAME)
                                 .contentJson(ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.READ))
                                 .asJson(Revision.class)
                                 .execute();

        assertThat(successResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(successResponse.content().major()).isGreaterThan(0);
    }

    @Test
    void updateProjectRolesWithoutGuestField() {
        // A missing guest field means a private repository, not an internal server error.
        final ResponseEntity<Revision> response =
                systemAdminClient.prepare()
                                 .post("/api/v1/metadata/{project}/repos/{repo}/roles/projects")
                                 .pathParam("project", PROJECT_NAME)
                                 .pathParam("repo", REPOSITORY_NAME)
                                 .content(MediaType.JSON, "{ \"member\": \"WRITE\" }")
                                 .asJson(Revision.class)
                                 .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        await().untilAsserted(
                () -> assertThat(projectMetadata().repo(REPOSITORY_NAME).roles().projectRoles().guest())
                        .isNull());
    }

    @Test
    void allowPublicRepositoriesLifecycle() {
        // Make the repository public.
        updateGuestRole(RepositoryRole.READ);

        // Cannot disallow public repositories while one exists.
        AggregatedHttpResponse response = putAllowPublicRepositories(false);
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).contains("Make the following repositories private first")
                                          .contains(REPOSITORY_NAME);

        // Make the repository private, then disallow.
        updateGuestRole(null);
        response = putAllowPublicRepositories(false);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        // Disallowing again is a no-op returning the current head revision.
        response = putAllowPublicRepositories(false);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        // Cannot make a repository public anymore.
        final AggregatedHttpResponse guestUpdateResponse =
                systemAdminClient.prepare()
                                 .post("/api/v1/metadata/{project}/repos/{repo}/roles/projects")
                                 .pathParam("project", PROJECT_NAME)
                                 .pathParam("repo", REPOSITORY_NAME)
                                 .contentJson(ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.READ))
                                 .execute();
        assertThat(guestUpdateResponse.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(guestUpdateResponse.contentUtf8())
                .contains("Public repositories are not allowed in the project");

        // A missing repository is reported as 404, not as a policy violation.
        final AggregatedHttpResponse notFound =
                systemAdminClient.prepare()
                                 .post("/api/v1/metadata/{project}/repos/{repo}/roles/projects")
                                 .pathParam("project", PROJECT_NAME)
                                 .pathParam("repo", "no_such_repo")
                                 .contentJson(ProjectRoles.of(RepositoryRole.WRITE, RepositoryRole.READ))
                                 .execute();
        assertThat(notFound.status()).isEqualTo(HttpStatus.NOT_FOUND);

        // Re-allow public repositories and make sure the repository can be public again.
        response = putAllowPublicRepositories(true);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        updateGuestRole(RepositoryRole.READ);
        // Restore the scaffold state.
        updateGuestRole(null);
    }

    private static void updateGuestRole(@Nullable RepositoryRole guestRole) {
        final ResponseEntity<Revision> response =
                systemAdminClient.prepare()
                                 .post("/api/v1/metadata/{project}/repos/{repo}/roles/projects")
                                 .pathParam("project", PROJECT_NAME)
                                 .pathParam("repo", REPOSITORY_NAME)
                                 .contentJson(ProjectRoles.of(RepositoryRole.WRITE, guestRole))
                                 .asJson(Revision.class)
                                 .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    private static AggregatedHttpResponse putAllowPublicRepositories(boolean allow) {
        return systemAdminClient.prepare()
                                .put("/api/v1/metadata/{project}/settings")
                                .pathParam("project", PROJECT_NAME)
                                .content(MediaType.JSON, "{ \"allowPublicRepositories\": " + allow + " }")
                                .execute();
    }

    @Test
    void repositoryAdminCanUpdateRepositoryMetadata() {
        addProjectMember();
        HttpRequest updateRepositoryProjectRolesRequest = updateRepositoryProjectRolesRequest();
        assertThat(memberTokenClient.execute(updateRepositoryProjectRolesRequest).status())
                .isSameAs(HttpStatus.FORBIDDEN);
        HttpRequest addUserRepositoryRoleRequest = addUserRepositoryRoleRequest();
        assertThat(memberTokenClient.execute(addUserRepositoryRoleRequest).status())
                .isSameAs(HttpStatus.FORBIDDEN);
        HttpRequest removeUserRepositoryRoleRequest = removeUserRepositoryRoleRequest();
        assertThat(memberTokenClient.execute(removeUserRepositoryRoleRequest).status())
                .isSameAs(HttpStatus.FORBIDDEN);

        // Promote the member to a repository admin.
        final HttpRequest req = HttpRequest.builder()
                                           .post("/api/v1/metadata/" + PROJECT_NAME + "/repos/" +
                                                 REPOSITORY_NAME + "/roles/appIdentities")
                                           .contentJson(new IdAndRepositoryRole("foo_token",
                                                                                RepositoryRole.ADMIN))
                                           .build();
        assertThat(systemAdminClient.execute(req).status()).isSameAs(HttpStatus.OK);

        // Now the member can update the repository metadata.
        updateRepositoryProjectRolesRequest = updateRepositoryProjectRolesRequest();
        assertThat(memberTokenClient.execute(updateRepositoryProjectRolesRequest).status())
                .isSameAs(HttpStatus.OK);
        addUserRepositoryRoleRequest = addUserRepositoryRoleRequest();
        assertThat(memberTokenClient.execute(addUserRepositoryRoleRequest).status())
                .isSameAs(HttpStatus.OK);
        removeUserRepositoryRoleRequest = removeUserRepositoryRoleRequest();
        assertThat(memberTokenClient.execute(removeUserRepositoryRoleRequest).status())
                .isSameAs(HttpStatus.NO_CONTENT);
        removeProjectMember();
    }

    @Test
    void repositoryAdminCanUpdateRepositoryMetadataWithCert() {
        addProjectMember();
        HttpRequest updateRepositoryProjectRolesRequest = updateRepositoryProjectRolesRequest();
        assertThat(memberCertClient.execute(updateRepositoryProjectRolesRequest).status())
                .isSameAs(HttpStatus.FORBIDDEN);
        HttpRequest addUserRepositoryRoleRequest = addUserRepositoryRoleRequest();
        assertThat(memberCertClient.execute(addUserRepositoryRoleRequest).status())
                .isSameAs(HttpStatus.FORBIDDEN);
        HttpRequest removeUserRepositoryRoleRequest = removeUserRepositoryRoleRequest();
        assertThat(memberCertClient.execute(removeUserRepositoryRoleRequest).status())
                .isSameAs(HttpStatus.FORBIDDEN);

        // Promote the member with certificate to a repository admin.
        final HttpRequest req = HttpRequest.builder()
                                           .post("/api/v1/metadata/" + PROJECT_NAME + "/repos/" +
                                                 REPOSITORY_NAME + "/roles/appIdentities")
                                           .contentJson(new IdAndRepositoryRole(MEMBER_CERTIFICATE_APP_ID,
                                                                                RepositoryRole.ADMIN))
                                           .build();
        assertThat(systemAdminClient.execute(req).status()).isSameAs(HttpStatus.OK);

        // Now the member with certificate can update the repository metadata.
        updateRepositoryProjectRolesRequest = updateRepositoryProjectRolesRequest();
        assertThat(memberCertClient.execute(updateRepositoryProjectRolesRequest).status())
                .isSameAs(HttpStatus.OK);
        addUserRepositoryRoleRequest = addUserRepositoryRoleRequest();
        assertThat(memberCertClient.execute(addUserRepositoryRoleRequest).status())
                .isSameAs(HttpStatus.OK);
        removeUserRepositoryRoleRequest = removeUserRepositoryRoleRequest();
        assertThat(memberCertClient.execute(removeUserRepositoryRoleRequest).status())
                .isSameAs(HttpStatus.NO_CONTENT);
        removeProjectMember();
    }

    @Test
    void updateAllowPublicRepositories() throws JsonProcessingException {
        // Use a dedicated project so the shared scaffold state is not mutated.
        final String project = "allow_public_proj";
        dogma.client().createProject(project).join();

        // A caller who is not the project owner is forbidden.
        final AggregatedHttpResponse forbidden =
                memberTokenClient.prepare()
                                 .put("/api/v1/metadata/{project}/settings")
                                 .pathParam("project", project)
                                 .content(MediaType.JSON, "{ \"allowPublicRepositories\": false }")
                                 .execute();
        assertThat(forbidden.status()).isSameAs(HttpStatus.FORBIDDEN);

        // A malformed body (non-boolean 'allow') is rejected with 400.
        final AggregatedHttpResponse badRequest =
                systemAdminClient.prepare()
                                 .put("/api/v1/metadata/{project}/settings")
                                 .pathParam("project", project)
                                 .content(MediaType.JSON, "{ \"allowPublicRepositories\": \"not-a-boolean\" }")
                                 .execute();
        assertThat(badRequest.status()).isSameAs(HttpStatus.BAD_REQUEST);

        // A body with no settings is rejected as well.
        final AggregatedHttpResponse emptySettings =
                systemAdminClient.prepare()
                                 .put("/api/v1/metadata/{project}/settings")
                                 .pathParam("project", project)
                                 .content(MediaType.JSON, "{}")
                                 .execute();
        assertThat(emptySettings.status()).isSameAs(HttpStatus.BAD_REQUEST);

        // A project MEMBER is forbidden as well.
        final HttpRequest addMember =
                HttpRequest.builder()
                           .post("/api/v1/metadata/" + project + "/appIdentities")
                           .contentJson(new IdAndProjectRole(MEMBER_TOKEN_APP_ID, ProjectRole.MEMBER))
                           .build();
        assertThat(systemAdminClient.execute(addMember).status()).isSameAs(HttpStatus.OK);
        final AggregatedHttpResponse memberForbidden =
                memberTokenClient.prepare()
                                 .put("/api/v1/metadata/{project}/settings")
                                 .pathParam("project", project)
                                 .content(MediaType.JSON, "{ \"allowPublicRepositories\": false }")
                                 .execute();
        assertThat(memberForbidden.status()).isSameAs(HttpStatus.FORBIDDEN);

        // A project OWNER who is not a system administrator can update the setting.
        final JsonPatch toOwner = JsonPatch.generate(Jackson.readTree("{\"role\":\"MEMBER\"}"),
                                                     Jackson.readTree("{\"role\":\"OWNER\"}"),
                                                     ReplaceMode.RFC6902);
        final HttpRequest promote =
                HttpRequest.builder()
                           .patch("/api/v1/metadata/" + project + "/appIdentities/" + MEMBER_TOKEN_APP_ID)
                           .content(MediaType.JSON_PATCH, Jackson.writeValueAsString(toOwner))
                           .build();
        assertThat(systemAdminClient.execute(promote).status()).isSameAs(HttpStatus.OK);
        final ResponseEntity<Revision> ok =
                memberTokenClient.prepare()
                                 .put("/api/v1/metadata/{project}/settings")
                                 .pathParam("project", project)
                                 .content(MediaType.JSON, "{ \"allowPublicRepositories\": false }")
                                 .asJson(Revision.class)
                                 .execute();
        assertThat(ok.status()).isSameAs(HttpStatus.OK);
        assertThat(ok.content().major()).isGreaterThan(0);

        await().untilAsserted(() -> {
            final ProjectMetadata metadata =
                    systemAdminClient.prepare()
                                     .get("/api/v1/projects/" + project)
                                     .asJson(ProjectMetadata.class, new ObjectMapper())
                                     .execute()
                                     .content();
            assertThat(metadata.allowPublicRepositories()).isFalse();
        });
    }

    private static ProjectMetadata projectMetadata() {
        return systemAdminClient.prepare()
                                .get("/api/v1/projects/" + PROJECT_NAME)
                                .asJson(ProjectMetadata.class, new ObjectMapper())
                                .execute()
                                .content();
    }

    private static HttpRequest updateRepositoryProjectRolesRequest() {
        return HttpRequest.builder()
                          .post("/api/v1/metadata/" + PROJECT_NAME + "/repos/" +
                                REPOSITORY_NAME + "/roles/projects")
                          .contentJson(ProjectRoles.of(RepositoryRole.READ, null))
                          .build();
    }

    private static HttpRequest addUserRepositoryRoleRequest() {
        return HttpRequest.builder()
                          .post("/api/v1/metadata/" + PROJECT_NAME + "/repos/" +
                                REPOSITORY_NAME + "/roles/users")
                          .contentJson(new IdAndRepositoryRole(MEMBER_ID, RepositoryRole.ADMIN))
                          .build();
    }

    private static HttpRequest removeUserRepositoryRoleRequest() {
        return HttpRequest.builder()
                          .delete("/api/v1/metadata/" + PROJECT_NAME + "/repos/" +
                                  REPOSITORY_NAME + "/roles/users/" + MEMBER_ID)
                          .build();
    }
}
