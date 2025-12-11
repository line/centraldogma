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

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.internal.jsonpatch.ReplaceMode;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.internal.api.MetadataApiService.IdAndProjectRole;
import com.linecorp.centraldogma.server.internal.api.MetadataApiService.IdAndRepositoryRole;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class MetadataApiServiceTest {

    private static final String PROJECT_NAME = "foo_proj";
    private static final String REPOSITORY_NAME = "foo_repo";

    private static final String MEMBER_ID = "member_id@linecorp.com";
    private static final String MEMBER_APP_ID = "foo";
    private static final String APP_ID = "app_id";

    @RegisterExtension
    static CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.systemAdministrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }
    };

    @SuppressWarnings("NotNullFieldNotInitialized")
    static BlockingWebClient systemAdminClient;
    @SuppressWarnings("NotNullFieldNotInitialized")
    static BlockingWebClient memberTokenClient;

    @BeforeAll
    static void setUp() throws JsonMappingException, JsonParseException {
        final String accessToken = getAccessToken(dogma.httpClient(),
                                                  TestAuthMessageUtil.USERNAME,
                                                  TestAuthMessageUtil.PASSWORD, "appId1", true);

        systemAdminClient = WebClient.builder(dogma.httpClient().uri())
                                     .auth(AuthToken.ofOAuth2(accessToken)).build()
                                     .blocking();
        RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, PROJECTS_PREFIX,
                                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        String body = "{\"name\": \"" + PROJECT_NAME + "\"}";
        // Create a project.
        assertThat(systemAdminClient.execute(headers, body).status())
                .isSameAs(HttpStatus.CREATED);

        headers = RequestHeaders.of(HttpMethod.POST, PROJECTS_PREFIX + '/' + PROJECT_NAME + "/repos",
                                    HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        body = "{\"name\": \"" + REPOSITORY_NAME + "\"}";
        // Create a repository.
        assertThat(systemAdminClient.execute(headers, body).status())
                .isSameAs(HttpStatus.CREATED);

        // Create a token
        HttpRequest request = HttpRequest.builder()
                                         .post("/api/v1/tokens")
                                         .content(MediaType.FORM_DATA, "appId=" + APP_ID)
                                         .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.CREATED);

        final String memberToken = getAccessToken(dogma.httpClient(),
                                                  TestAuthMessageUtil.USERNAME,
                                                  TestAuthMessageUtil.PASSWORD, MEMBER_APP_ID, false);

        // Add as a member to the project
        request = HttpRequest.builder()
                             .post("/api/v1/metadata/" + PROJECT_NAME + "/tokens")
                             .contentJson(new IdAndProjectRole(MEMBER_APP_ID, ProjectRole.MEMBER))
                             .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.OK);

        memberTokenClient = WebClient.builder(dogma.httpClient().uri())
                                     .auth(AuthToken.ofOAuth2(memberToken)).build()
                                     .blocking();
    }

    @Test
    void addUpdateAndRemoveProjectMember() throws JsonProcessingException {
        addProjectMember();
        final JsonPatch jsonPatch = JsonPatch.generate(Jackson.readTree("{\"role\":\"MEMBER\"}"),
                                                       Jackson.readTree("{\"role\":\"OWNER\"}"),
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

        final JsonPatch jsonPatch = JsonPatch.generate(Jackson.readTree("{\"role\":\"MEMBER\"}"),
                                                       Jackson.readTree("{\"role\":\"OWNER\"}"),
                                                       ReplaceMode.RFC6902);
        // [{"op":"replace","path":"/role","value":"OWNER"}]
        // Update the token
        request = HttpRequest.builder()
                             .patch("/api/v1/metadata/" + PROJECT_NAME + "/tokens/app_id")
                             .content(MediaType.JSON_PATCH, Jackson.writeValueAsString(jsonPatch))
                             .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.OK);

        // Remove the token
        request = HttpRequest.builder()
                             .delete("/api/v1/metadata/" + PROJECT_NAME + "/tokens/app_id")
                             .build();
        assertThat(systemAdminClient.execute(request).status())
                .isSameAs(HttpStatus.NO_CONTENT);
    }

    private static void addProjectToken() {
        final HttpRequest request =
                HttpRequest.builder()
                           .post("/api/v1/metadata/" + PROJECT_NAME + "/tokens")
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
                                               REPOSITORY_NAME + "/roles/tokens")
                                         .contentJson(new IdAndRepositoryRole(APP_ID, RepositoryRole.READ))
                                         .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.OK);

        ProjectMetadata projectMetadata = projectMetadata();
        assertThat(projectMetadata.repo(REPOSITORY_NAME).roles().tokens().get(APP_ID))
                .isSameAs(RepositoryRole.READ);

        // Remove the member
        request = HttpRequest.builder()
                             .delete("/api/v1/metadata/" + PROJECT_NAME + "/tokens/" + APP_ID)
                             .build();
        assertThat(systemAdminClient.execute(request).status())
                .isSameAs(HttpStatus.NO_CONTENT);
        projectMetadata = projectMetadata();
        assertThat(projectMetadata.repo(REPOSITORY_NAME).roles().tokens().get(APP_ID)).isNull();
    }

    @Test
    void grantRoleToMemberForMetaRepository() throws Exception {
        final AggregatedHttpResponse res =
                memberTokenClient.get("/api/v1/projects/" + PROJECT_NAME + "/repos/meta/list");
        // A member isn't allowed to access the meta repository.
        assertThat(res.status()).isSameAs(HttpStatus.FORBIDDEN);
        assertThat(res.contentUtf8()).contains(
                "Repository 'foo_proj/meta' can be accessed only by a system administrator.");

        // Can't give a READ role to the member.
        final HttpRequest request =
                HttpRequest.builder()
                           .post("/api/v1/metadata/" + PROJECT_NAME + "/repos/meta/roles/projects")
                           .contentJson(ProjectRoles.of(RepositoryRole.READ, null))
                           .build();
        assertThat(systemAdminClient.execute(request).status()).isSameAs(HttpStatus.BAD_REQUEST);

        // The member cannot access the meta repository.
        assertThat(memberTokenClient.get(
                "/api/v1/projects/" + PROJECT_NAME + "/repos/meta/list").status())
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
                                                 REPOSITORY_NAME + "/roles/tokens")
                                           .contentJson(new IdAndRepositoryRole("foo", RepositoryRole.ADMIN))
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
