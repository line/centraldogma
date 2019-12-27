/*
 * Copyright 2017 LINE Corporation
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
import java.net.InetSocketAddress;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.centraldogma.common.ProjectExistsException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.testing.junit4.CentralDogmaRule;

public class ProjectServiceV1Test {

    // TODO(minwoox) replace this unit test using nested structure in junit 5
    // Rule is used instead of ClassRule because the listProject and listRemovedProject are
    // affected by other unit tests.
    @Rule
    public final CentralDogmaRule dogma = new CentralDogmaRule();

    private static WebClient webClient;

    @Before
    public void init() {
        final InetSocketAddress serverAddress = dogma.dogma().activePort().get().localAddress();
        final String serverUri = "http://127.0.0.1:" + serverAddress.getPort();
        webClient = WebClient.builder(serverUri)
                             .addHttpHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                             .build();
    }

    @Test
    public void createProject() throws IOException {
        final AggregatedHttpResponse aRes = createProject("myPro");
        final ResponseHeaders headers = ResponseHeaders.of(aRes.headers());
        assertThat(headers.status()).isEqualTo(HttpStatus.CREATED);

        final String location = headers.get(HttpHeaderNames.LOCATION);
        assertThat(location).isEqualTo("/api/v1/projects/myPro");

        final JsonNode jsonNode = Jackson.readTree(aRes.contentUtf8());
        assertThat(jsonNode.get("name").asText()).isEqualTo("myPro");
        assertThat(jsonNode.get("createdAt").asText()).isNotNull();
    }

    private static AggregatedHttpResponse createProject(String name) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, PROJECTS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);

        final String body = "{\"name\": \"" + name + "\"}";
        return webClient.execute(headers, body).aggregate().join();
    }

    @Test
    public void createProjectWithSameName() {
        createProject("myPro");
        final AggregatedHttpResponse res = createProject("myPro");
        assertThat(ResponseHeaders.of(res.headers()).status()).isEqualTo(HttpStatus.CONFLICT);
        final String expectedJson =
                '{' +
                "   \"exception\": \"" + ProjectExistsException.class.getName() + "\"," +
                "   \"message\": \"Project 'myPro' exists already.\"" +
                '}';
        assertThatJson(res.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    public void listProjects() {
        createProject("trustin");
        createProject("hyangtack");
        createProject("minwoox");
        final AggregatedHttpResponse aRes = webClient.get(PROJECTS_PREFIX).aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.OK);
        final String expectedJson =
                '[' +
                "   {" +
                "       \"name\": \"hyangtack\"," +
                "       \"creator\": {" +
                "           \"name\": \"System\"," +
                "           \"email\": \"system@localhost.localdomain\"" +
                "       }," +
                "       \"url\": \"/api/v1/projects/hyangtack\"," +
                "       \"createdAt\": \"${json-unit.ignore}\"" +
                "   }," +
                "   {" +
                "       \"name\": \"minwoox\"," +
                "       \"creator\": {" +
                "           \"name\": \"System\"," +
                "           \"email\": \"system@localhost.localdomain\"" +
                "       }," +
                "       \"url\": \"/api/v1/projects/minwoox\"," +
                "       \"createdAt\": \"${json-unit.ignore}\"" +
                "   }," +
                "   {" +
                "       \"name\": \"trustin\"," +
                "       \"creator\": {" +
                "           \"name\": \"System\"," +
                "           \"email\": \"system@localhost.localdomain\"" +
                "       }," +
                "       \"url\": \"/api/v1/projects/trustin\"," +
                "       \"createdAt\": \"${json-unit.ignore}\"" +
                "   }" +
                ']';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    public void removeProject() {
        createProject("foo");
        final AggregatedHttpResponse aRes = webClient.delete(PROJECTS_PREFIX + "/foo")
                                                     .aggregate().join();
        final ResponseHeaders headers = ResponseHeaders.of(aRes.headers());
        assertThat(ResponseHeaders.of(headers).status()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    public void removeAbsentProject() {
        final AggregatedHttpResponse aRes = webClient.delete(PROJECTS_PREFIX + "/foo")
                                                     .aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    public void purgeProject() {
        removeProject();
        final AggregatedHttpResponse aRes = webClient.delete(PROJECTS_PREFIX + "/foo/removed")
                                                     .aggregate().join();
        final ResponseHeaders headers = ResponseHeaders.of(aRes.headers());
        assertThat(ResponseHeaders.of(headers).status()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    public void listRemovedProjects() throws IOException {
        createProject("trustin");
        createProject("hyangtack");
        createProject("minwoox");
        webClient.delete(PROJECTS_PREFIX + "/hyangtack").aggregate().join();
        webClient.delete(PROJECTS_PREFIX + "/minwoox").aggregate().join();

        final AggregatedHttpResponse removedRes = webClient.get(PROJECTS_PREFIX + "?status=removed")
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

        final AggregatedHttpResponse remainedRes = webClient.get(PROJECTS_PREFIX).aggregate().join();
        final String remains = remainedRes.contentUtf8();
        final JsonNode jsonNode = Jackson.readTree(remains);

        // only trustin project is left
        assertThat(jsonNode.size()).isOne();
    }

    @Test
    public void unremoveProject() throws IOException {
        createProject("bar");
        final String projectPath = PROJECTS_PREFIX + "/bar";
        webClient.delete(projectPath).aggregate().join();

        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH, projectPath,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_PATCH);

        final String unremovePatch = "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]";
        final AggregatedHttpResponse aRes = webClient.execute(headers, unremovePatch).aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.OK);
        final String expectedJson =
                '{' +
                "   \"name\": \"bar\"," +
                "   \"creator\": {" +
                "       \"name\": \"System\"," +
                "       \"email\": \"system@localhost.localdomain\"" +
                "   }," +
                "   \"url\": \"/api/v1/projects/bar\"," +
                "   \"createdAt\": \"${json-unit.ignore}\"" +
                '}';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    public void unremoveAbsentProject() {
        final String projectPath = PROJECTS_PREFIX + "/bar";
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH, projectPath,
                                                         HttpHeaderNames.CONTENT_TYPE,
                                                         "application/json-patch+json");

        final String unremovePatch = "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]";
        final AggregatedHttpResponse aRes = webClient.execute(headers, unremovePatch).aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
