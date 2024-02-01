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
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class ProjectServiceV1Test {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.administrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }
    };

    private WebClient adminClient;
    private WebClient normalClient;

    @BeforeEach
    void setUp() throws JsonProcessingException, UnknownHostException {
        final URI uri = dogma.httpClient().uri();
        adminClient = WebClient.builder(uri)
                               .auth(AuthToken.ofOAuth2(sessionId(dogma.httpClient(),
                                                                  TestAuthMessageUtil.USERNAME,
                                                                  TestAuthMessageUtil.PASSWORD)))
                               .build();
        normalClient = WebClient.builder(uri)
                                .auth(AuthToken.ofOAuth2(sessionId(dogma.httpClient(),
                                                                   TestAuthMessageUtil.USERNAME2,
                                                                   TestAuthMessageUtil.PASSWORD2)))
                                .build();
    }

    static String sessionId(WebClient webClient, String username, String password)
            throws JsonParseException, JsonMappingException {
        return Jackson.readValue(TestAuthMessageUtil.login(webClient, username, password).content().array(),
                                 AccessToken.class).accessToken();
    }

    @Test
    void createProject() throws IOException {
        final AggregatedHttpResponse aRes = createProject(normalClient, "myPro");
        final ResponseHeaders headers = ResponseHeaders.of(aRes.headers());
        assertThat(headers.status()).isEqualTo(HttpStatus.CREATED);

        final String location = headers.get(HttpHeaderNames.LOCATION);
        assertThat(location).isEqualTo("/api/v1/projects/myPro");

        final JsonNode jsonNode = Jackson.readTree(aRes.contentUtf8());
        assertThat(jsonNode.get("name").asText()).isEqualTo("myPro");
        assertThat(jsonNode.get("createdAt").asText()).isNotNull();
    }

    static AggregatedHttpResponse createProject(WebClient client, String name) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, PROJECTS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);

        final String body = "{\"name\": \"" + name + "\"}";
        return client.execute(headers, body).aggregate().join();
    }

    @Test
    void createProjectWithSameName() {
        createProject(normalClient, "myNewPro");
        final AggregatedHttpResponse res = createProject(normalClient, "myNewPro");
        assertThat(ResponseHeaders.of(res.headers()).status()).isEqualTo(HttpStatus.CONFLICT);
        final String expectedJson =
                '{' +
                "   \"exception\": \"" + ProjectExistsException.class.getName() + "\"," +
                "   \"message\": \"Project 'myNewPro' exists already.\"" +
                '}';
        assertThatJson(res.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    void removeProject() {
        createProject(normalClient, "foo");
        assertThat(normalClient.delete(PROJECTS_PREFIX + "/foo")
                               .aggregate()
                               .join()
                               .headers()
                               .status()).isEqualTo(HttpStatus.NO_CONTENT);

        // Cannot remove internal dogma project.
        assertThat(adminClient.delete(PROJECTS_PREFIX + "/dogma")
                              .aggregate()
                              .join()
                              .headers()
                              .status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void removeAbsentProject() {
        final AggregatedHttpResponse aRes = normalClient.delete(PROJECTS_PREFIX + "/foo")
                                                        .aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void purgeProject() {
        removeProject();
        assertThat(adminClient.delete(PROJECTS_PREFIX + "/foo/removed")
                              .aggregate()
                              .join()
                              .headers()
                              .status()).isEqualTo(HttpStatus.NO_CONTENT);

        // Illegal access to the internal project.
        assertThat(adminClient.delete(PROJECTS_PREFIX + "/dogma/removed")
                              .aggregate()
                              .join()
                              .headers()
                              .status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unremoveProject() {
        createProject(normalClient, "bar");

        final String projectPath = PROJECTS_PREFIX + "/bar";
        normalClient.delete(projectPath).aggregate().join();

        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH, projectPath,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH);

        final String unremovePatch = "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]";
        final AggregatedHttpResponse aRes = adminClient.execute(headers, unremovePatch).aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.OK);
        final String expectedJson =
                '{' +
                "   \"name\": \"bar\"," +
                "   \"creator\": {" +
                "       \"name\": \"" + TestAuthMessageUtil.USERNAME2 + "\"," +
                "       \"email\": \"" + TestAuthMessageUtil.USERNAME2 + "@localhost.localdomain\"" +
                "   }," +
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
        final AggregatedHttpResponse aRes = adminClient.execute(headers, unremovePatch).aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
