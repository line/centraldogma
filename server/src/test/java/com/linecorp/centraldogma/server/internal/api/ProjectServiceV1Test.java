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

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class ProjectServiceV1Test {

    // TODO(minwoox) replace this unit test using nested structure in junit 5
    // Rule is used instead of ClassRule because the listProject and listRemovedProject are
    // affected by other unit tests.
    @Rule
    public final CentralDogmaRule dogma = new CentralDogmaRule();

    private static HttpClient httpClient;

    @Before
    public void init() {
        final InetSocketAddress serverAddress = dogma.dogma().activePort().get().localAddress();
        final String serverUri = "http://" + serverAddress.getHostString() + ':' + serverAddress.getPort();
        httpClient = new HttpClientBuilder(serverUri)
                .addHttpHeader(HttpHeaderNames.AUTHORIZATION, "bearer anonymous").build();
    }

    @Test
    public void createProject() throws IOException {
        final AggregatedHttpMessage aRes = createProject("myPro");
        final HttpHeaders headers = aRes.headers();
        assertThat(headers.status()).isEqualTo(HttpStatus.CREATED);

        final String location = headers.get(HttpHeaderNames.LOCATION);
        assertThat(location).isEqualTo("/api/v1/projects/myPro");

        final JsonNode jsonNode = Jackson.readTree(aRes.content().toStringUtf8());
        assertThat(jsonNode.get("name").asText()).isEqualTo("myPro");
        assertThat(jsonNode.get("createdAt").asText()).isNotNull();
    }

    private AggregatedHttpMessage createProject(String name) {
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, PROJECTS_PREFIX)
                                               .contentType(MediaType.JSON);

        final String body = "{\"name\": \"" + name + "\"}";
        return httpClient.execute(headers, body).aggregate().join();
    }

    @Test
    public void createProjectWithSameName() {
        createProject("myPro");
        final AggregatedHttpMessage res = createProject("myPro");
        assertThat(res.headers().status()).isEqualTo(HttpStatus.CONFLICT);
        final String expectedJson =
                '{' +
                "   \"message\": \"project: myPro already exists.\"" +
                '}';
        assertThatJson(res.content().toStringUtf8()).isEqualTo(expectedJson);
    }

    @Test
    public void listProjects() {
        createProject("trustin");
        createProject("hyangtack");
        createProject("minwoox");
        final AggregatedHttpMessage aRes = httpClient.get(PROJECTS_PREFIX).aggregate().join();
        assertThat(aRes.headers().status()).isEqualTo(HttpStatus.OK);
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
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void removeProject() {
        createProject("foo");
        final AggregatedHttpMessage aRes = httpClient.delete(PROJECTS_PREFIX + "/foo")
                                                     .aggregate().join();
        final HttpHeaders headers = aRes.headers();
        assertThat(headers.status()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    public void removeAbsentProject() {
        final AggregatedHttpMessage aRes = httpClient.delete(PROJECTS_PREFIX + "/foo")
                                                     .aggregate().join();
        assertThat(aRes.headers().status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    public void listRemovedProjects() throws IOException {
        createProject("trustin");
        createProject("hyangtack");
        createProject("minwoox");
        httpClient.delete(PROJECTS_PREFIX + "/hyangtack").aggregate().join();
        httpClient.delete(PROJECTS_PREFIX + "/minwoox").aggregate().join();

        final AggregatedHttpMessage removedRes = httpClient.get(PROJECTS_PREFIX + "?status=removed")
                                                           .aggregate().join();
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
        final String actualJson = removedRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);

        final AggregatedHttpMessage remainedRes = httpClient.get(PROJECTS_PREFIX).aggregate().join();
        final String remains = remainedRes.content().toStringUtf8();
        final JsonNode jsonNode = Jackson.readTree(remains);

        // only trustin project is left
        assertThat(jsonNode.size()).isOne();
    }

    @Test
    public void unremoveProject() throws IOException {
        createProject("bar");
        final String projectPath = PROJECTS_PREFIX + "/bar";
        httpClient.delete(projectPath).aggregate().join();

        final HttpHeaders headers = HttpHeaders.of(HttpMethod.PATCH, projectPath)
                                               .contentType(MediaType.JSON_PATCH);

        final String unremovePatch = "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]";
        final AggregatedHttpMessage aRes = httpClient.execute(headers, unremovePatch).aggregate().join();
        assertThat(aRes.headers().status()).isEqualTo(HttpStatus.OK);
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
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void unremoveAbsentProject() {
        final String projectPath = PROJECTS_PREFIX + "/bar";
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.PATCH, projectPath)
                                               .add(HttpHeaderNames.CONTENT_TYPE,
                                                    "application/json-patch+json");

        final String unremovePatch = "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]";
        final AggregatedHttpMessage aRes = httpClient.execute(headers, unremovePatch).aggregate().join();
        assertThat(aRes.headers().status()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
