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
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.login;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.common.RepositoryRole;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
import com.linecorp.centraldogma.internal.jsonpatch.JsonPatch;
import com.linecorp.centraldogma.internal.jsonpatch.ReplaceMode;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class MetadataApiServiceTest {

    private static final String PROJECT_NAME = "foo_proj";
    private static final String REPOSITORY_NAME = "foo_repo";

    private static final String MEMBER_ID = "member_id@linecorp.com";
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
    static WebClient systemAdminClient;

    @BeforeAll
    static void setUp() throws JsonMappingException, JsonParseException {
        final AggregatedHttpResponse response = login(dogma.httpClient(),
                                                      TestAuthMessageUtil.USERNAME,
                                                      TestAuthMessageUtil.PASSWORD);

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        final String sessionId = Jackson.readValue(response.content().array(), AccessToken.class)
                                        .accessToken();
        systemAdminClient = WebClient.builder(dogma.httpClient().uri())
                                     .auth(AuthToken.ofOAuth2(sessionId)).build();
        RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, PROJECTS_PREFIX,
                                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        String body = "{\"name\": \"" + PROJECT_NAME + "\"}";
        // Create a project.
        assertThat(systemAdminClient.execute(headers, body).aggregate().join().status())
                .isSameAs(HttpStatus.CREATED);

        headers = RequestHeaders.of(HttpMethod.POST, PROJECTS_PREFIX + '/' + PROJECT_NAME + "/repos",
                                    HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        body = "{\"name\": \"" + REPOSITORY_NAME + "\"}";
        // Create a repository.
        assertThat(systemAdminClient.execute(headers, body).aggregate().join().status())
                .isSameAs(HttpStatus.CREATED);

        // Create a token
        final HttpRequest request = HttpRequest.builder()
                                               .post("/api/v1/tokens")
                                               .content(MediaType.FORM_DATA, "appId=" + APP_ID)
                                               .build();
        assertThat(systemAdminClient.execute(request).aggregate().join().status()).isSameAs(HttpStatus.CREATED);
    }

    @Test
    void addUpdateAndRemoveProjectMember() throws JsonProcessingException {
        addProjectMember();
        final JsonPatch jsonPatch = JsonPatch.generate(Jackson.readTree("{\"role\":\"MEMBER\"}}"),
                                                       Jackson.readTree("{\"role\":\"OWNER\"}}"),
                                                       ReplaceMode.RFC6902);
        // [{"op":"replace","path":"/role","value":"OWNER"}]
        // Update the member
        HttpRequest request = HttpRequest.builder()
                                         .patch("/api/v1/metadata/" + PROJECT_NAME + "/members/" + MEMBER_ID)
                                         .content(MediaType.JSON_PATCH, Jackson.writeValueAsString(jsonPatch))
                                         .build();
        assertThat(systemAdminClient.execute(request).aggregate().join().status()).isSameAs(HttpStatus.OK);

        // Remove the member
        request = HttpRequest.builder()
                             .delete("/api/v1/metadata/" + PROJECT_NAME + "/members/" + MEMBER_ID)
                             .build();
        assertThat(systemAdminClient.execute(request).aggregate().join().status())
                .isSameAs(HttpStatus.NO_CONTENT);
    }

    private static void addProjectMember() {
        final HttpRequest request = HttpRequest.builder()
                                               .post("/api/v1/metadata/" + PROJECT_NAME + "/members")
                                               .content(MediaType.JSON,
                                                        '{' +
                                                        "\"id\":\"" + MEMBER_ID + "\"," +
                                                        "\"role\":\"MEMBER\"" +
                                                        '}')
                                               .build();
        assertThat(systemAdminClient.execute(request).aggregate().join().status()).isSameAs(HttpStatus.OK);
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
                             .patch("/api/v1/metadata/" + PROJECT_NAME + "/tokens/app_id")
                             .content(MediaType.JSON_PATCH, Jackson.writeValueAsString(jsonPatch))
                             .build();
        assertThat(systemAdminClient.execute(request).aggregate().join().status()).isSameAs(HttpStatus.OK);

        // Remove the token
        request = HttpRequest.builder()
                             .delete("/api/v1/metadata/" + PROJECT_NAME + "/tokens/app_id")
                             .build();
        assertThat(systemAdminClient.execute(request).aggregate().join().status())
                .isSameAs(HttpStatus.NO_CONTENT);
    }

    private static void addProjectToken() {
        final HttpRequest request = HttpRequest.builder()
                                               .post("/api/v1/metadata/" + PROJECT_NAME + "/tokens")
                                               .content(MediaType.JSON,
                                                        '{' +
                                                        "\"id\":\"app_id\"," +
                                                        "\"role\":\"MEMBER\"" +
                                                        '}')
                                               .build();
        assertThat(systemAdminClient.execute(request).aggregate().join().status()).isSameAs(HttpStatus.OK);
    }

    @Test
    void addUpdateAndRemoveRepositoryUser() throws JsonProcessingException {
        addProjectMember();
        HttpRequest request = HttpRequest.builder()
                                         .post("/api/v1/metadata/" + PROJECT_NAME + "/repos/" +
                                               REPOSITORY_NAME + "/roles/users")
                                         .content(MediaType.JSON,
                                                  '{' +
                                                  "\"id\":\"" + MEMBER_ID + "\"," +
                                                  "\"role\":\"READ\"" +
                                                  '}')
                                         .build();
        assertThat(systemAdminClient.execute(request).aggregate().join().status()).isSameAs(HttpStatus.OK);

        ProjectMetadata projectMetadata = projectMetadata();
        assertThat(projectMetadata.repo(REPOSITORY_NAME).roles().users().get(MEMBER_ID))
                .isSameAs(RepositoryRole.READ);

        // Remove the member
        request = HttpRequest.builder()
                             .delete("/api/v1/metadata/" + PROJECT_NAME + "/members/" + MEMBER_ID)
                             .build();
        assertThat(systemAdminClient.execute(request).aggregate().join().status())
                .isSameAs(HttpStatus.NO_CONTENT);
        projectMetadata = projectMetadata();
        assertThat(projectMetadata.repo(REPOSITORY_NAME).roles().users().get(MEMBER_ID)).isNull();
    }

    @Test
    void addUpdateAndRemoveRepositoryToken() throws JsonProcessingException {
        addProjectToken();
        HttpRequest request = HttpRequest.builder()
                                         .post("/api/v1/metadata/" + PROJECT_NAME + "/repos/" +
                                               REPOSITORY_NAME + "/roles/tokens")
                                         .content(MediaType.JSON,
                                                  '{' +
                                                  "\"id\":\"" + APP_ID + "\"," +
                                                  "\"role\":\"READ\"" +
                                                  '}')
                                         .build();
        assertThat(systemAdminClient.execute(request).aggregate().join().status()).isSameAs(HttpStatus.OK);

        ProjectMetadata projectMetadata = projectMetadata();
        assertThat(projectMetadata.repo(REPOSITORY_NAME).roles().tokens().get(APP_ID))
                .isSameAs(RepositoryRole.READ);

        // Remove the member
        request = HttpRequest.builder()
                             .delete("/api/v1/metadata/" + PROJECT_NAME + "/tokens/" + APP_ID)
                             .build();
        assertThat(systemAdminClient.execute(request).aggregate().join().status())
                .isSameAs(HttpStatus.NO_CONTENT);
        projectMetadata = projectMetadata();
        assertThat(projectMetadata.repo(REPOSITORY_NAME).roles().tokens().get(APP_ID)).isNull();
    }

    @Test
    void grantRoleToMemberForMetaRepository() throws Exception {
        final String memberToken = "appToken-secret-member";
        // Create a token with a non-random secret.
        HttpRequest request = HttpRequest.builder()
                                         .post("/api/v1/tokens")
                                         .content(MediaType.FORM_DATA,
                                                  "secret=" + memberToken + "&isSystemAdmin=false&appId=foo")
                                         .build();
        AggregatedHttpResponse res = systemAdminClient.execute(request).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.CREATED);
        res = systemAdminClient.get("/api/v1/tokens").aggregate().join();
        assertThat(res.contentUtf8()).contains("\"secret\":\"" + memberToken + '"');

        // Add as a member to the project
        request = HttpRequest.builder()
                             .post("/api/v1/metadata/" + PROJECT_NAME + "/tokens")
                             .content(MediaType.JSON,
                                      '{' +
                                      "\"id\":\"foo\"," +
                                      "\"role\":\"MEMBER\"" +
                                      '}')
                             .build();
        res = systemAdminClient.execute(request).aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        final WebClient memberClient = WebClient.builder(dogma.httpClient().uri())
                                                .auth(AuthToken.ofOAuth2(memberToken)).build();
        res = memberClient.get("/api/v1/projects/" + PROJECT_NAME + "/repos/meta/list").aggregate().join();
        // A member isn't allowed to access the meta repository yet.
        assertThat(res.status()).isSameAs(HttpStatus.FORBIDDEN);
        assertThat(res.contentUtf8()).contains(
                "You must have the READ repository role to access the 'foo_proj/meta'");

        // Grant a READ role to the member.
        request = HttpRequest.builder()
                             .post("/api/v1/metadata/" + PROJECT_NAME + "/repos/meta/roles/projects")
                             .content(MediaType.JSON,
                                      '{' +
                                      "  \"member\": \"READ\"," +
                                      "  \"guest\": null" +
                                      '}')
                             .build();
        assertThat(systemAdminClient.execute(request).aggregate().join().status()).isSameAs(HttpStatus.OK);

        // Now the member can access the meta repository.
        res = memberClient.get("/api/v1/projects/" + PROJECT_NAME + "/repos/meta/list").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.NO_CONTENT);

        // Revoke the role
        request = HttpRequest.builder()
                             .post("/api/v1/metadata/" + PROJECT_NAME + "/repos/meta/roles/projects")
                             .content(MediaType.JSON,
                                      '{' +
                                      "  \"member\": null," +
                                      "  \"guest\": null" +
                                      '}')
                             .build();
        assertThat(systemAdminClient.execute(request).aggregate().join().status()).isSameAs(HttpStatus.OK);

        // Now the member cannot access the meta repository.
        res = memberClient.get("/api/v1/projects/" + PROJECT_NAME + "/repos/meta/list").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.FORBIDDEN);
    }

    private static ProjectMetadata projectMetadata()
            throws JsonParseException, JsonMappingException {
        return Jackson.readValue(
                systemAdminClient.prepare()
                                 .get("/api/v1/projects/" + PROJECT_NAME)
                                 .execute().aggregate().join().contentUtf8(), ProjectMetadata.class);
    }
}
