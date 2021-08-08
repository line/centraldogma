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
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.REPOS;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.internal.jackson.Jackson;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class RepositoryServiceV1Test {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            builder.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        }
    };

    private static final String REPOS_PREFIX = PROJECTS_PREFIX + "/myPro" + REPOS;

    @BeforeAll
    static void setUp() {
        createProject(dogma);
    }

    @Test
    void createRepository() throws IOException {
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse aRes = createRepository(client, "myRepo");
        final ResponseHeaders headers = ResponseHeaders.of(aRes.headers());
        assertThat(headers.status()).isEqualTo(HttpStatus.CREATED);

        final String location = headers.get(HttpHeaderNames.LOCATION);
        assertThat(location).isEqualTo("/api/v1/projects/myPro/repos/myRepo");

        final JsonNode jsonNode = Jackson.ofJson().readTree(aRes.contentUtf8());
        assertThat(jsonNode.get("name").asText()).isEqualTo("myRepo");
        assertThat(jsonNode.get("headRevision").asInt()).isOne();
        assertThat(jsonNode.get("createdAt").asText()).isNotNull();
    }

    private static AggregatedHttpResponse createRepository(WebClient client, String repoName) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, REPOS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final String body = "{\"name\": \"" + repoName + "\"}";

        return client.execute(headers, body).aggregate().join();
    }

    @Test
    void createRepositoryWithSameName() {
        final WebClient client = dogma.httpClient();
        createRepository(client, "myNewRepo");

        // create again with the same name
        final AggregatedHttpResponse aRes = createRepository(client, "myNewRepo");
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.CONFLICT);
        final String expectedJson =
                '{' +
                "  \"exception\": \"" + RepositoryExistsException.class.getName() + "\"," +
                "  \"message\": \"Repository 'myPro/myNewRepo' exists already.\"" +
                '}';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    void createRepositoryInAbsentProject() {
        final WebClient client = dogma.httpClient();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST,
                                                         PROJECTS_PREFIX + "/absentProject" + REPOS,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final String body = "{\"name\": \"myRepo\"}";
        final AggregatedHttpResponse aRes = client.execute(headers, body).aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
        final String expectedJson =
                '{' +
                "  \"exception\": \"" + ProjectNotFoundException.class.getName() + "\"," +
                "  \"message\": \"Project 'absentProject' does not exist.\"" +
                '}';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    void removeRepository() {
        final WebClient client = dogma.httpClient();
        createRepository(client,"foo");
        final AggregatedHttpResponse aRes = client.delete(REPOS_PREFIX + "/foo").aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void removeAbsentRepository() {
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse aRes = client.delete(REPOS_PREFIX + "/foo").aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removeMetaRepository() {
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse aRes = client.delete(REPOS_PREFIX + '/' + Project.REPO_META)
                                                  .aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unremoveAbsentRepository() {
        final WebClient client = dogma.httpClient();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH, REPOS_PREFIX + "/baz",
                                                         HttpHeaderNames.CONTENT_TYPE,
                                                         "application/json-patch+json");

        final String unremovePatch = "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]";
        final AggregatedHttpResponse aRes = client.execute(headers, unremovePatch).aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
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
            createProject(dogma);
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
                    "           \"name\": \"System\"," +
                    "           \"email\": \"system@localhost.localdomain\"" +
                    "       }," +
                    "       \"headRevision\": \"${json-unit.ignore}\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/dogma\"," +
                    "       \"createdAt\": \"${json-unit.ignore}\"" +
                    "   }," +
                    "   {" +
                    "       \"name\": \"meta\"," +
                    "       \"creator\": {" +
                    "           \"name\": \"System\"," +
                    "           \"email\": \"system@localhost.localdomain\"" +
                    "       }," +
                    "       \"headRevision\": \"${json-unit.ignore}\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/meta\"," +
                    "       \"createdAt\": \"${json-unit.ignore}\"" +
                    "   }," +
                    "   {" +
                    "       \"name\": \"myRepo\"," +
                    "       \"creator\": {" +
                    "           \"name\": \"admin\"," +
                    "           \"email\": \"admin@localhost.localdomain\"" +
                    "       }," +
                    "       \"headRevision\": \"${json-unit.ignore}\"," +
                    "       \"url\": \"/api/v1/projects/myPro/repos/myRepo\"," +
                    "       \"createdAt\": \"${json-unit.ignore}\"" +
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
            final JsonNode jsonNode = Jackson.ofJson().readTree(remains);

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
                    "   \"createdAt\": \"${json-unit.ignore}\"" +
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

    private static void createProject(CentralDogmaExtension dogma) {
        // the default project used for unit tests
        final String body = "{\"name\": \"myPro\"}";
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, PROJECTS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);

        final WebClient client = dogma.httpClient();
        client.execute(headers, body).aggregate().join();
    }
}
