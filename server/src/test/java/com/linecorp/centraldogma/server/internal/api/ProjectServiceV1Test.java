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

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getSessionCookie;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.login;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.InvalidHttpResponseException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.internal.api.v1.ProjectDto;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.internal.admin.auth.SessionUtil;
import com.linecorp.centraldogma.server.metadata.Member;
import com.linecorp.centraldogma.server.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.metadata.TokenRegistration;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class ProjectServiceV1Test {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.systemAdministrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }
    };

    private static final ObjectMapper mapper = new ObjectMapper();

    private static BlockingWebClient systemAdminClient;
    private static BlockingWebClient userClient;
    private static BlockingWebClient tokenClient;

    @BeforeAll
    static void setUp() throws JsonProcessingException, UnknownHostException {
        final URI uri = dogma.httpClient().uri();
        systemAdminClient = WebClient.builder(uri)
                                     .auth(AuthToken.ofOAuth2(getAccessToken(dogma.httpClient(),
                                                                             TestAuthMessageUtil.USERNAME,
                                                                             TestAuthMessageUtil.PASSWORD,
                                                                             "appId1",
                                                                             true)))
                                     .build()
                                     .blocking();
        final AggregatedHttpResponse response = login(dogma.httpClient(), TestAuthMessageUtil.USERNAME2,
                                                      TestAuthMessageUtil.PASSWORD2);
        final Cookie sessionCookie = getSessionCookie(response);
        final String csrfToken = Jackson.readTree(response.contentUtf8()).get("csrf_token").asText();
        userClient = WebClient.builder(uri)
                              .addHeader(SessionUtil.X_CSRF_TOKEN, csrfToken)
                              .addHeader(HttpHeaderNames.COOKIE, sessionCookie.toCookieHeader())
                              .build()
                              .blocking();
        tokenClient = WebClient.builder(uri)
                               .auth(AuthToken.ofOAuth2(getAccessToken(dogma.httpClient(),
                                                                       TestAuthMessageUtil.USERNAME2,
                                                                       TestAuthMessageUtil.PASSWORD2,
                                                                       "appId2",
                                                                       false)))
                               .build()
                               .blocking();
    }

    @Test
    void createProject() {
        ProjectMetadata projectMetadata = createProjectAndReturnMetadata(userClient, "myPro");
        assertThat(projectMetadata.tokens()).isEmpty();
        assertThat(projectMetadata.members().size()).isOne();
        final Member member =
                projectMetadata.members().get(TestAuthMessageUtil.USERNAME2 + Util.USER_EMAIL_SUFFIX);
        assertThat(member.login()).isEqualTo(TestAuthMessageUtil.USERNAME2 + Util.USER_EMAIL_SUFFIX);
        assertThat(member.role()).isEqualTo(ProjectRole.OWNER);

        projectMetadata = createProjectAndReturnMetadata(tokenClient, "myPro2");
        assertThat(projectMetadata.members()).isEmpty();
        assertThat(projectMetadata.tokens().size()).isOne();
        final TokenRegistration tokenRegistration = projectMetadata.tokens().get("appId2");
        assertThat(tokenRegistration.id()).isEqualTo("appId2");
        assertThat(tokenRegistration.role()).isEqualTo(ProjectRole.OWNER);
    }

    static ResponseEntity<ProjectDto> createProject(BlockingWebClient client, String name) {
        return client.prepare()
                     .post(PROJECTS_PREFIX)
                     .contentJson(ImmutableMap.of("name", name))
                     .asJson(ProjectDto.class, mapper)
                     .execute();
    }

    private static ProjectMetadata createProjectAndReturnMetadata(
            BlockingWebClient client, String projectName) {
        final ResponseEntity<ProjectDto> response = createProject(client, projectName);
        final ResponseHeaders headers = ResponseHeaders.of(response.headers());
        assertThat(headers.status()).isEqualTo(HttpStatus.CREATED);

        final String location = headers.get(HttpHeaderNames.LOCATION);
        assertThat(location).isEqualTo("/api/v1/projects/" + projectName);

        final ProjectDto project = response.content();
        assertThat(project.name()).isEqualTo(projectName);
        assertThat(project.createdAt()).isNotNull();
        assertThat(project.userRole()).isEqualTo(ProjectRole.OWNER);

        return client.prepare().get(PROJECTS_PREFIX + '/' + projectName)
                     .asJson(ProjectMetadata.class, mapper).execute()
                     .content();
    }

    @Test
    void createInvalidProject() throws IOException {
        // @ is only allowed for internal projects.
        assertThatThrownBy(() -> {
            createProject(userClient, "@myPro");
        }).isInstanceOfSatisfying(InvalidHttpResponseException.class, cause -> {
            assertThat(cause.response().headers().status()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
    }

    @Test
    void createProjectWithSameName() {
        createProject(userClient, "myNewPro");
        assertThatThrownBy(() -> {
            createProject(userClient, "myNewPro");
        }).isInstanceOfSatisfying(InvalidHttpResponseException.class, cause -> {
            final AggregatedHttpResponse response = cause.response();
            assertThat(response.headers().status()).isEqualTo(HttpStatus.CONFLICT);
            final String expectedJson =
                    '{' +
                    "   \"exception\": \"" + ProjectExistsException.class.getName() + "\"," +
                    "   \"message\": \"project already exists: myNewPro\"" +
                    '}';
            assertThatJson(response.contentUtf8()).isEqualTo(expectedJson);
        });
    }

    @Test
    void removeProject() {
        createProject(userClient, "foo");
        assertThat(userClient.delete(PROJECTS_PREFIX + "/foo")
                             .headers()
                             .status()).isEqualTo(HttpStatus.NO_CONTENT);

        // Cannot remove internal dogma project.
        assertThat(systemAdminClient.delete(PROJECTS_PREFIX + "/dogma")
                                    .headers()
                                    .status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void removeAbsentProject() {
        final AggregatedHttpResponse aRes = userClient.delete(PROJECTS_PREFIX + "/foo");
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void purgeProject() {
        removeProject();
        assertThat(systemAdminClient.delete(PROJECTS_PREFIX + "/foo/removed")
                                    .headers()
                                    .status()).isEqualTo(HttpStatus.NO_CONTENT);

        // Illegal access to the internal project.
        assertThat(systemAdminClient.delete(PROJECTS_PREFIX + "/dogma/removed")
                                    .headers()
                                    .status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unremoveProject() {
        createProject(userClient, "bar");

        final String projectPath = PROJECTS_PREFIX + "/bar";
        userClient.delete(projectPath);

        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH, projectPath,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH);

        final String unremovePatch = "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]";
        final AggregatedHttpResponse aRes = systemAdminClient.execute(headers, unremovePatch);
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.OK);
        final String expectedJson =
                '{' +
                "   \"name\": \"bar\"," +
                "   \"creator\": {" +
                "       \"name\": \"" + TestAuthMessageUtil.USERNAME2 + "\"," +
                "       \"email\": \"" + TestAuthMessageUtil.USERNAME2 + "@localhost.localdomain\"" +
                "   }," +
                "   \"userRole\":\"OWNER\"," +
                "   \"url\": \"/api/v1/projects/bar\"," +
                "   \"createdAt\": \"${json-unit.ignore}\"" +
                '}';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    void unremoveAbsentProject() {
        final String projectPath = PROJECTS_PREFIX + "/bar";
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH, projectPath,
                                                         HttpHeaderNames.CONTENT_TYPE,
                                                         "application/json-patch+json");

        final String unremovePatch = "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]";
        final AggregatedHttpResponse aRes = systemAdminClient.execute(headers, unremovePatch);
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
