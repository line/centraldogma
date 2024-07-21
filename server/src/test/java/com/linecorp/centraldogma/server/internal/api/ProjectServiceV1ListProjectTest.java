/*
 * Copyright 2024 LINE Corporation
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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.server.internal.api.ProjectServiceV1Test.createProject;
import static com.linecorp.centraldogma.server.internal.api.ProjectServiceV1Test.sessionId;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import net.javacrumbs.jsonunit.core.Option;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.ProjectDto;
import com.linecorp.centraldogma.internal.api.v1.ProjectRoleDto;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.internal.api.MetadataApiService.IdentifierWithRole;
import com.linecorp.centraldogma.server.metadata.Token;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class ProjectServiceV1ListProjectTest {

    private static ObjectMapper mapper = new ObjectMapper();

    @RegisterExtension
    final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.administrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    private BlockingWebClient adminClient;
    private BlockingWebClient normalClient;

    @BeforeEach
    void setUp() throws JsonProcessingException {
        final URI uri = dogma.httpClient().uri();
        adminClient = WebClient.builder(uri)
                               .auth(AuthToken.ofOAuth2(sessionId(dogma.httpClient(),
                                                                  TestAuthMessageUtil.USERNAME,
                                                                  TestAuthMessageUtil.PASSWORD)))
                               .build()
                               .blocking();
        normalClient = WebClient.builder(uri)
                                .auth(AuthToken.ofOAuth2(sessionId(dogma.httpClient(),
                                                                   TestAuthMessageUtil.USERNAME2,
                                                                   TestAuthMessageUtil.PASSWORD2)))
                                .build()
                                .blocking();
    }

    @Test
    void listProjects() {
        // Create an internal project.
        dogma.projectManager().create("@foo", Author.SYSTEM);

        createProject(normalClient, "trustin");
        createProject(normalClient, "hyangtack");
        createProject(normalClient, "minwoox");
        createProject(adminClient, "jrhee17");

        AggregatedHttpResponse aRes = normalClient.get(PROJECTS_PREFIX);
        assertThat(aRes.headers().status()).isEqualTo(HttpStatus.OK);
        final String normalUserExpect =
                '[' +
                "   {" +
                "       \"name\": \"hyangtack\"," +
                "       \"creator\": {" +
                "           \"name\": \"" + TestAuthMessageUtil.USERNAME2 + "\"," +
                "           \"email\": \"" + TestAuthMessageUtil.USERNAME2 + "@localhost.localdomain\"" +
                "       }," +
                "       \"userRole\":\"OWNER\"," +
                "       \"url\": \"/api/v1/projects/hyangtack\"," +
                "       \"createdAt\": \"${json-unit.ignore}\"" +
                "   }," +
                "   {" +
                "       \"name\": \"minwoox\"," +
                "       \"creator\": {" +
                "           \"name\": \"" + TestAuthMessageUtil.USERNAME2 + "\"," +
                "           \"email\": \"" + TestAuthMessageUtil.USERNAME2 + "@localhost.localdomain\"" +
                "       }," +
                "        \"userRole\":\"OWNER\"," +
                "       \"url\": \"/api/v1/projects/minwoox\"," +
                "       \"createdAt\": \"${json-unit.ignore}\"" +
                "   }," +
                "   {" +
                "       \"name\": \"trustin\"," +
                "       \"creator\": {" +
                "           \"name\": \"" + TestAuthMessageUtil.USERNAME2 + "\"," +
                "           \"email\": \"" + TestAuthMessageUtil.USERNAME2 + "@localhost.localdomain\"" +
                "       }," +
                "       \"userRole\":\"OWNER\"," +
                "       \"url\": \"/api/v1/projects/trustin\"," +
                "       \"createdAt\": \"${json-unit.ignore}\"" +
                "   }," +
                "   {" +
                "       \"name\": \"jrhee17\"," +
                "       \"creator\": {" +
                "           \"name\": \"" + TestAuthMessageUtil.USERNAME + "\"," +
                "           \"email\": \"" + TestAuthMessageUtil.USERNAME + "@localhost.localdomain\"" +
                "       }," +
                "       \"userRole\":\"GUEST\"," +
                "       \"url\": \"/api/v1/projects/jrhee17\"," +
                "       \"createdAt\": \"${json-unit.ignore}\"" +
                "   }" +
                ']';
        assertThatJson(aRes.contentUtf8())
                .when(Option.IGNORING_ARRAY_ORDER)
                .isEqualTo(String.format(normalUserExpect, ""));

        // Admin fetches internal project "dogma" as well.
        aRes = adminClient.get(PROJECTS_PREFIX);
        assertThat(aRes.headers().status()).isEqualTo(HttpStatus.OK);

        final String adminUserExpect =
                '[' +
                "   {" +
                "       \"name\": \"@foo\"," +
                "       \"creator\": {" +
                "           \"name\": \"System\"," +
                "           \"email\": \"system@localhost.localdomain\"" +
                "        }," +
                "        \"userRole\":\"OWNER\"," +
                "        \"url\": \"/api/v1/projects/@foo\"," +
                "        \"createdAt\": \"${json-unit.ignore}\"" +
                "   }," +
                "   {" +
                "       \"name\": \"dogma\"," +
                "       \"creator\": {" +
                "           \"name\": \"System\"," +
                "           \"email\": \"system@localhost.localdomain\"" +
                "        }," +
                "        \"userRole\":\"OWNER\"," +
                "        \"url\": \"/api/v1/projects/dogma\"," +
                "        \"createdAt\": \"${json-unit.ignore}\"" +
                "   }," +
                "   {" +
                "       \"name\": \"hyangtack\"," +
                "       \"creator\": {" +
                "           \"name\": \"" + TestAuthMessageUtil.USERNAME2 + "\"," +
                "           \"email\": \"" + TestAuthMessageUtil.USERNAME2 + "@localhost.localdomain\"" +
                "       }," +
                "       \"userRole\":\"OWNER\"," +
                "       \"url\": \"/api/v1/projects/hyangtack\"," +
                "       \"createdAt\": \"${json-unit.ignore}\"" +
                "   }," +
                "   {" +
                "       \"name\": \"minwoox\"," +
                "       \"creator\": {" +
                "           \"name\": \"" + TestAuthMessageUtil.USERNAME2 + "\"," +
                "           \"email\": \"" + TestAuthMessageUtil.USERNAME2 + "@localhost.localdomain\"" +
                "       }," +
                "        \"userRole\":\"OWNER\"," +
                "       \"url\": \"/api/v1/projects/minwoox\"," +
                "       \"createdAt\": \"${json-unit.ignore}\"" +
                "   }," +
                "   {" +
                "       \"name\": \"trustin\"," +
                "       \"creator\": {" +
                "           \"name\": \"" + TestAuthMessageUtil.USERNAME2 + "\"," +
                "           \"email\": \"" + TestAuthMessageUtil.USERNAME2 + "@localhost.localdomain\"" +
                "       }," +
                "       \"userRole\":\"OWNER\"," +
                "       \"url\": \"/api/v1/projects/trustin\"," +
                "       \"createdAt\": \"${json-unit.ignore}\"" +
                "   }," +
                "   {" +
                "       \"name\": \"jrhee17\"," +
                "       \"creator\": {" +
                "           \"name\": \"" + TestAuthMessageUtil.USERNAME + "\"," +
                "           \"email\": \"" + TestAuthMessageUtil.USERNAME + "@localhost.localdomain\"" +
                "       }," +
                "       \"userRole\":\"OWNER\"," +
                "       \"url\": \"/api/v1/projects/jrhee17\"," +
                "       \"createdAt\": \"${json-unit.ignore}\"" +
                "   }" +
                ']';
        assertThatJson(aRes.contentUtf8())
                .when(Option.IGNORING_ARRAY_ORDER)
                .isEqualTo(adminUserExpect);
    }

    @Test
    void listRemovedProjects() throws IOException {
        createProject(normalClient, "trustin");
        createProject(normalClient, "hyangtack");
        createProject(normalClient, "minwoox");
        normalClient.delete(PROJECTS_PREFIX + "/hyangtack");
        normalClient.delete(PROJECTS_PREFIX + "/minwoox");

        final AggregatedHttpResponse removedRes = normalClient.get(PROJECTS_PREFIX + "?status=removed");
        assertThat(removedRes.headers().status()).isEqualTo(HttpStatus.OK);
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

        final AggregatedHttpResponse remainedRes = normalClient.get(PROJECTS_PREFIX);
        final String remains = remainedRes.contentUtf8();
        final JsonNode jsonNode = Jackson.readTree(remains);

        // Only trustin project is left
        assertThat(jsonNode.size()).isOne();
    }

    @Test
    void userRoleWithLoginUser() {
        createProject(adminClient, "trustin");
        createProject(adminClient, "hyangtack");

        Map<String, ProjectDto> projects = getProjects(normalClient);
        assertThat(projects).hasSize(2);
        assertThat(projects).containsOnlyKeys("trustin", "hyangtack");
        assertThat(projects.values().stream().map(ProjectDto::userRole))
                .containsExactlyInAnyOrder(ProjectRoleDto.GUEST, ProjectRoleDto.GUEST);

        AggregatedHttpResponse aRes =
                adminClient.prepare()
                           .post("/api/v1/metadata/trustin/members")
                           .contentJson(new IdentifierWithRole(
                                   TestAuthMessageUtil.USERNAME2, "MEMBER"))
                           .execute();
        assertThat(aRes.status()).isEqualTo(HttpStatus.OK);
        projects = getProjects(normalClient);
        assertThat(projects.get("trustin").userRole()).isEqualTo(ProjectRoleDto.MEMBER);
        assertThat(projects.get("hyangtack").userRole()).isEqualTo(ProjectRoleDto.GUEST);

        aRes = adminClient.prepare()
                          .post("/api/v1/metadata/hyangtack/members")
                          .contentJson(new IdentifierWithRole(
                                  TestAuthMessageUtil.USERNAME2, "OWNER"))
                          .execute();
        assertThat(aRes.status()).isEqualTo(HttpStatus.OK);
        projects = getProjects(normalClient);
        assertThat(projects.get("trustin").userRole()).isEqualTo(ProjectRoleDto.MEMBER);
        assertThat(projects.get("hyangtack").userRole()).isEqualTo(ProjectRoleDto.OWNER);
    }

    @Test
    void userRoleWithToken() {
        createProject(normalClient, "trustin");
        createProject(normalClient, "hyangtack");

        final String appId = "app-abc";
        final ResponseEntity<Token> tokenResponse =
                normalClient.prepare()
                            .post("/api/v1/tokens")
                            .queryParam("appId", appId)
                            .asJson(Token.class, mapper)
                            .execute();
        assertThat(tokenResponse.status()).isEqualTo(HttpStatus.CREATED);
        final String token = tokenResponse.content().secret();
        final BlockingWebClient tokenClient = WebClient.builder(dogma.httpClient().uri())
                                                       .auth(AuthToken.ofOAuth2(token))
                                                       .build()
                                                       .blocking();
        Map<String, ProjectDto> projects = getProjects(tokenClient);
        assertThat(projects).hasSize(2);
        assertThat(projects).containsOnlyKeys("trustin", "hyangtack");
        assertThat(projects.values().stream().map(ProjectDto::userRole))
                .containsExactlyInAnyOrder(ProjectRoleDto.GUEST, ProjectRoleDto.GUEST);

        AggregatedHttpResponse aRes =
                normalClient.prepare()
                            .post("/api/v1/metadata/trustin/tokens")
                            .contentJson(new IdentifierWithRole(appId, "MEMBER"))
                            .execute();
        assertThat(aRes.status()).isEqualTo(HttpStatus.OK);
        projects = getProjects(tokenClient);
        assertThat(projects.get("trustin").userRole()).isEqualTo(ProjectRoleDto.MEMBER);
        assertThat(projects.get("hyangtack").userRole()).isEqualTo(ProjectRoleDto.GUEST);

        aRes = normalClient.prepare()
                           .post("/api/v1/metadata/hyangtack/tokens")
                           .contentJson(new IdentifierWithRole(appId, "OWNER"))
                           .execute();
        assertThat(aRes.status()).isEqualTo(HttpStatus.OK);
        projects = getProjects(tokenClient);
        assertThat(projects.get("trustin").userRole()).isEqualTo(ProjectRoleDto.MEMBER);
        assertThat(projects.get("hyangtack").userRole()).isEqualTo(ProjectRoleDto.OWNER);
    }

    private Map<String, ProjectDto> getProjects(BlockingWebClient client) {
        final ResponseEntity<List<ProjectDto>> response =
                client.prepare()
                      .get(PROJECTS_PREFIX)
                      .asJson(new TypeReference<List<ProjectDto>>() {})
                      .execute();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        return response.content().stream()
                       .collect(toImmutableMap(ProjectDto::name, Function.identity()));
    }
}
