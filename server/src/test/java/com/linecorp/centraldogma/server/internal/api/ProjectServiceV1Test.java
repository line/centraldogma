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
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class ProjectServiceV1Test {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            builder.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        }
    };

    @Test
    void createProject() throws IOException {
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse aRes = createProject(client, "myPro");
        final ResponseHeaders headers = ResponseHeaders.of(aRes.headers());
        assertThat(headers.status()).isEqualTo(HttpStatus.CREATED);

        final String location = headers.get(HttpHeaderNames.LOCATION);
        assertThat(location).isEqualTo("/api/v1/projects/myPro");

        final JsonNode jsonNode = Jackson.readTree(aRes.contentUtf8());
        assertThat(jsonNode.get("name").asText()).isEqualTo("myPro");
        assertThat(jsonNode.get("createdAt").asText()).isNotNull();
    }

    private static AggregatedHttpResponse createProject(WebClient client, String name) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, PROJECTS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);

        final String body = "{\"name\": \"" + name + "\"}";
        return client.execute(headers, body).aggregate().join();
    }

    @Test
    void createProjectWithSameName() {
        final WebClient client = dogma.httpClient();
        createProject(client, "myNewPro");
        final AggregatedHttpResponse res = createProject(client, "myNewPro");
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
        final WebClient client = dogma.httpClient();
        createProject(client, "foo");
        final AggregatedHttpResponse aRes = client.delete(PROJECTS_PREFIX + "/foo")
                                                  .aggregate().join();
        final ResponseHeaders headers = ResponseHeaders.of(aRes.headers());
        assertThat(ResponseHeaders.of(headers).status()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void removeAbsentProject() {
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse aRes = client.delete(PROJECTS_PREFIX + "/foo")
                                                  .aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void purgeProject() {
        removeProject();

        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse aRes = client.delete(PROJECTS_PREFIX + "/foo/removed")
                                                  .aggregate().join();
        final ResponseHeaders headers = ResponseHeaders.of(aRes.headers());
        assertThat(ResponseHeaders.of(headers).status()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void unremoveProject() {
        final WebClient client = dogma.httpClient();
        createProject(client, "bar");

        final String projectPath = PROJECTS_PREFIX + "/bar";
        client.delete(projectPath).aggregate().join();

        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH, projectPath,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH);

        final String unremovePatch = "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]";
        final AggregatedHttpResponse aRes = client.execute(headers, unremovePatch).aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.OK);
        final String expectedJson =
                '{' +
                "   \"name\": \"bar\"," +
                "   \"creator\": {" +
                "       \"name\": \"admin\"," +
                "       \"email\": \"admin@localhost.localdomain\"" +
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
        final WebClient client = dogma.httpClient();
        final AggregatedHttpResponse aRes = client.execute(headers, unremovePatch).aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Nested
    class ListProjectsTest {

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

        @Test
        void listProjects() {
            final WebClient client = dogma.httpClient();
            createProject(client, "trustin");
            createProject(client, "hyangtack");
            createProject(client, "minwoox");

            final AggregatedHttpResponse aRes = client.get(PROJECTS_PREFIX).aggregate().join();
            assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.OK);
            final String expectedJson =
                    '[' +
                    "   {" +
                    "       \"name\": \"hyangtack\"," +
                    "       \"creator\": {" +
                    "           \"name\": \"admin\"," +
                    "           \"email\": \"admin@localhost.localdomain\"" +
                    "       }," +
                    "       \"url\": \"/api/v1/projects/hyangtack\"," +
                    "       \"createdAt\": \"${json-unit.ignore}\"" +
                    "   }," +
                    "   {" +
                    "       \"name\": \"minwoox\"," +
                    "       \"creator\": {" +
                    "           \"name\": \"admin\"," +
                    "           \"email\": \"admin@localhost.localdomain\"" +
                    "       }," +
                    "       \"url\": \"/api/v1/projects/minwoox\"," +
                    "       \"createdAt\": \"${json-unit.ignore}\"" +
                    "   }," +
                    "   {" +
                    "       \"name\": \"trustin\"," +
                    "       \"creator\": {" +
                    "           \"name\": \"admin\"," +
                    "           \"email\": \"admin@localhost.localdomain\"" +
                    "       }," +
                    "       \"url\": \"/api/v1/projects/trustin\"," +
                    "       \"createdAt\": \"${json-unit.ignore}\"" +
                    "   }" +
                    ']';
            assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
        }

        @Test
        void listRemovedProjects() throws IOException {
            final WebClient client = dogma.httpClient();
            createProject(client, "trustin");
            createProject(client, "hyangtack");
            createProject(client, "minwoox");
            client.delete(PROJECTS_PREFIX + "/hyangtack").aggregate().join();
            client.delete(PROJECTS_PREFIX + "/minwoox").aggregate().join();

            final AggregatedHttpResponse removedRes = client.get(PROJECTS_PREFIX + "?status=removed")
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

            final AggregatedHttpResponse remainedRes = client.get(PROJECTS_PREFIX).aggregate().join();
            final String remains = remainedRes.contentUtf8();
            final JsonNode jsonNode = Jackson.readTree(remains);

            // Only trustin project is left
            assertThat(jsonNode.size()).isOne();
        }
    }
}
