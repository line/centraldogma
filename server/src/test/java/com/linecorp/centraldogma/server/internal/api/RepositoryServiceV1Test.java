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
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.REPOS;
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

public class RepositoryServiceV1Test {

    // TODO(minwoox) replace this unit test using nested structure in junit 5
    // Rule is used instead of ClassRule because the listRepository and listRemovedRepository are
    // affected by other unit tests.
    @Rule
    public final CentralDogmaRule dogma = new CentralDogmaRule();

    private static final String REPOS_PREFIX = PROJECTS_PREFIX + "/myPro" + REPOS;

    private static HttpClient httpClient;

    @Before
    public void init() {
        final InetSocketAddress serverAddress = dogma.dogma().activePort().get().localAddress();
        final String serverUri = "http://" + serverAddress.getHostString() + ':' + serverAddress.getPort();
        httpClient = new HttpClientBuilder(serverUri)
                .addHttpHeader(HttpHeaderNames.AUTHORIZATION, "bearer anonymous").build();

        // the default project used for unit tests
        final String body = "{\"name\": \"myPro\"}";
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, PROJECTS_PREFIX)
                                               .contentType(MediaType.JSON);

        httpClient.execute(headers, body).aggregate().join();
    }

    @Test
    public void createRepository() throws IOException {
        final AggregatedHttpMessage aRes = createRepository("myRepo");
        final HttpHeaders headers = aRes.headers();
        assertThat(headers.status()).isEqualTo(HttpStatus.CREATED);

        final String location = headers.get(HttpHeaderNames.LOCATION);
        assertThat(location).isEqualTo("/api/v1/projects/myPro/repos/myRepo");

        final JsonNode jsonNode = Jackson.readTree(aRes.content().toStringUtf8());
        assertThat(jsonNode.get("name").asText()).isEqualTo("myRepo");
        assertThat(jsonNode.get("headRevision").asInt()).isOne();
        assertThat(jsonNode.get("createdAt").asText()).isNotNull();
    }

    private AggregatedHttpMessage createRepository(String repoName) {
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, REPOS_PREFIX)
                                               .contentType(MediaType.JSON);
        final String body = "{\"name\": \"" + repoName + "\"}";

        return httpClient.execute(headers, body).aggregate().join();
    }

    @Test
    public void createRepositoryWithSameName() {
        createRepository("myRepo");

        // create again with the same name
        final AggregatedHttpMessage aRes = createRepository("myRepo");
        assertThat(aRes.headers().status()).isEqualTo(HttpStatus.CONFLICT);
        final String expectedJson =
                '{' +
                "   \"message\": \"myRepo already exists.\"" +
                '}';
        assertThatJson(aRes.content().toStringUtf8()).isEqualTo(expectedJson);
    }

    @Test
    public void createRepositoryInAbsentProject() {
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.POST, PROJECTS_PREFIX + "/absentProject" + REPOS)
                                               .contentType(MediaType.JSON);
        final String body = "{\"name\": \"myRepo\"}";
        final AggregatedHttpMessage aRes = httpClient.execute(headers, body).aggregate().join();
        assertThat(aRes.headers().status()).isEqualTo(HttpStatus.NOT_FOUND);
        final String expectedJson =
                '{' +
                "   \"message\": \"absentProject does not exist.\"" +
                '}';
        assertThatJson(aRes.content().toStringUtf8()).isEqualTo(expectedJson);
    }

    @Test
    public void listRepositories() {
        createRepository("myRepo");
        final AggregatedHttpMessage aRes = httpClient.get(REPOS_PREFIX).aggregate().join();

        assertThat(aRes.headers().status()).isEqualTo(HttpStatus.OK);
        final String expectedJson =
                '[' +
                "   {" +
                "       \"name\": \"main\"," +
                "       \"creator\": {" +
                "           \"name\": \"System\"," +
                "           \"email\": \"system@localhost.localdomain\"" +
                "       }," +
                "       \"headRevision\": \"${json-unit.ignore}\"," +
                "       \"url\": \"/api/v1/projects/myPro/repos/main\"," +
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
                "           \"name\": \"System\"," +
                "           \"email\": \"system@localhost.localdomain\"" +
                "       }," +
                "       \"headRevision\": \"${json-unit.ignore}\"," +
                "       \"url\": \"/api/v1/projects/myPro/repos/myRepo\"," +
                "       \"createdAt\": \"${json-unit.ignore}\"" +
                "   }" +
                ']';
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void removeRepository() {
        createRepository("foo");
        final AggregatedHttpMessage aRes = httpClient.delete(REPOS_PREFIX + "/foo").aggregate().join();
        final HttpHeaders headers = aRes.headers();
        assertThat(headers.status()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    public void removeAbsentRepository() {
        final AggregatedHttpMessage aRes = httpClient.delete(REPOS_PREFIX + "/foo").aggregate().join();
        final HttpHeaders headers = aRes.headers();
        assertThat(headers.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    public void listRemovedRepositories() throws IOException {
        createRepository("trustin");
        createRepository("hyangtack");
        createRepository("minwoox");
        httpClient.delete(REPOS_PREFIX + "/hyangtack").aggregate().join();
        httpClient.delete(REPOS_PREFIX + "/minwoox").aggregate().join();

        final AggregatedHttpMessage removedRes = httpClient.get(REPOS_PREFIX + "?status=removed")
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

        final AggregatedHttpMessage remainedRes = httpClient.get(REPOS_PREFIX).aggregate().join();
        final String remains = remainedRes.content().toStringUtf8();
        final JsonNode jsonNode = Jackson.readTree(remains);

        // main, meta and trustin repositories are left
        assertThat(jsonNode.size()).isEqualTo(3);
    }

    @Test
    public void unremoveRepository() {
        createRepository("foo");
        httpClient.delete(REPOS_PREFIX + "/foo").aggregate().join();
        final HttpHeaders reqHeaders = HttpHeaders.of(HttpMethod.PATCH, REPOS_PREFIX + "/foo")
                                                  .add(HttpHeaderNames.CONTENT_TYPE,
                                                       "application/json-patch+json");

        final String unremovePatch = "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]";
        final AggregatedHttpMessage aRes = httpClient.execute(reqHeaders, unremovePatch).aggregate().join();
        final HttpHeaders headers = aRes.headers();
        assertThat(headers.status()).isEqualTo(HttpStatus.OK);
        final String expectedJson =
                '{' +
                "   \"name\": \"foo\"," +
                "   \"creator\": {" +
                "       \"name\": \"System\"," +
                "       \"email\": \"system@localhost.localdomain\"" +
                "   }," +
                "   \"headRevision\": 1," +
                "   \"url\": \"/api/v1/projects/myPro/repos/foo\"," +
                "   \"createdAt\": \"${json-unit.ignore}\"" +
                '}';
        final String actualJson = aRes.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }

    @Test
    public void unremoveAbsentRepository() {
        final String repoPath = REPOS_PREFIX + "/baz";
        final HttpHeaders headers = HttpHeaders.of(HttpMethod.PATCH, repoPath)
                                               .add(HttpHeaderNames.CONTENT_TYPE,
                                                    "application/json-patch+json");

        final String unremovePatch = "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]";
        final AggregatedHttpMessage aRes = httpClient.execute(headers, unremovePatch).aggregate().join();
        assertThat(aRes.headers().status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    public void normalizeRevision() {
        createRepository("foo");
        final AggregatedHttpMessage res = httpClient.get(REPOS_PREFIX + "/foo/revision/-1")
                                                    .aggregate().join();
        final String expectedJson = "{\"revision\":1}";
        final String actualJson = res.content().toStringUtf8();
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }
}
