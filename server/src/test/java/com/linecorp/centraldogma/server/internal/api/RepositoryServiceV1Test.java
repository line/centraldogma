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

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.centraldogma.common.ProjectNotFoundException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class RepositoryServiceV1Test {

    // TODO(minwoox) replace this unit test using nested structure in junit 5
    // Rule is used instead of ClassRule because the listRepository and listRemovedRepository are
    // affected by other unit tests.
    @Rule
    public final CentralDogmaRule dogma = new CentralDogmaRule();

    private static final String REPOS_PREFIX = PROJECTS_PREFIX + "/myPro" + REPOS;

    private static WebClient webClient;

    @Before
    public void init() {
        final InetSocketAddress serverAddress = dogma.dogma().activePort().get().localAddress();
        final String serverUri = "http://127.0.0.1:" + serverAddress.getPort();
        webClient = WebClient.builder(serverUri)
                             .addHttpHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                             .build();

        // the default project used for unit tests
        final String body = "{\"name\": \"myPro\"}";
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, PROJECTS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);

        webClient.execute(headers, body).aggregate().join();
    }

    @Test
    public void createRepository() throws IOException {
        final AggregatedHttpResponse aRes = createRepository("myRepo");
        final ResponseHeaders headers = ResponseHeaders.of(aRes.headers());
        assertThat(headers.status()).isEqualTo(HttpStatus.CREATED);

        final String location = headers.get(HttpHeaderNames.LOCATION);
        assertThat(location).isEqualTo("/api/v1/projects/myPro/repos/myRepo");

        final JsonNode jsonNode = Jackson.readTree(aRes.contentUtf8());
        assertThat(jsonNode.get("name").asText()).isEqualTo("myRepo");
        assertThat(jsonNode.get("headRevision").asInt()).isOne();
        assertThat(jsonNode.get("createdAt").asText()).isNotNull();
    }

    private static AggregatedHttpResponse createRepository(String repoName) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, REPOS_PREFIX,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final String body = "{\"name\": \"" + repoName + "\"}";

        return webClient.execute(headers, body).aggregate().join();
    }

    @Test
    public void createRepositoryWithSameName() {
        createRepository("myRepo");

        // create again with the same name
        final AggregatedHttpResponse aRes = createRepository("myRepo");
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.CONFLICT);
        final String expectedJson =
                '{' +
                "  \"exception\": \"" + RepositoryExistsException.class.getName() + "\"," +
                "  \"message\": \"Repository 'myPro/myRepo' exists already.\"" +
                '}';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    public void createRepositoryInAbsentProject() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST,
                                                         PROJECTS_PREFIX + "/absentProject" + REPOS,
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final String body = "{\"name\": \"myRepo\"}";
        final AggregatedHttpResponse aRes = webClient.execute(headers, body).aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
        final String expectedJson =
                '{' +
                "  \"exception\": \"" + ProjectNotFoundException.class.getName() + "\"," +
                "  \"message\": \"Project 'absentProject' does not exist.\"" +
                '}';
        assertThatJson(aRes.contentUtf8()).isEqualTo(expectedJson);
    }

    @Test
    public void listRepositories() {
        createRepository("myRepo");
        final AggregatedHttpResponse aRes = webClient.get(REPOS_PREFIX).aggregate().join();

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
    public void removeRepository() {
        createRepository("foo");
        final AggregatedHttpResponse aRes = webClient.delete(REPOS_PREFIX + "/foo").aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    public void removeAbsentRepository() {
        final AggregatedHttpResponse aRes = webClient.delete(REPOS_PREFIX + "/foo").aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    public void removeMetaRepository() {
        final AggregatedHttpResponse aRes = webClient.delete(REPOS_PREFIX + '/' + Project.REPO_META)
                                                     .aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    public void listRemovedRepositories() throws IOException {
        createRepository("trustin");
        createRepository("hyangtack");
        createRepository("minwoox");
        webClient.delete(REPOS_PREFIX + "/hyangtack").aggregate().join();
        webClient.delete(REPOS_PREFIX + "/minwoox").aggregate().join();

        final AggregatedHttpResponse removedRes = webClient.get(REPOS_PREFIX + "?status=removed")
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

        final AggregatedHttpResponse remainedRes = webClient.get(REPOS_PREFIX).aggregate().join();
        final String remains = remainedRes.contentUtf8();
        final JsonNode jsonNode = Jackson.readTree(remains);

        // dogma, meta and trustin repositories are left
        assertThat(jsonNode.size()).isEqualTo(3);
    }

    @Test
    public void unremoveRepository() {
        createRepository("foo");
        webClient.delete(REPOS_PREFIX + "/foo").aggregate().join();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH, REPOS_PREFIX + "/foo",
                                                         HttpHeaderNames.CONTENT_TYPE,
                                                         "application/json-patch+json");

        final String unremovePatch = "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]";
        final AggregatedHttpResponse aRes = webClient.execute(headers, unremovePatch).aggregate().join();
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
    public void unremoveAbsentRepository() {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH, REPOS_PREFIX + "/baz",
                                                         HttpHeaderNames.CONTENT_TYPE,
                                                         "application/json-patch+json");

        final String unremovePatch = "[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]";
        final AggregatedHttpResponse aRes = webClient.execute(headers, unremovePatch).aggregate().join();
        assertThat(ResponseHeaders.of(aRes.headers()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    public void normalizeRevision() {
        createRepository("foo");
        final AggregatedHttpResponse res = webClient.get(REPOS_PREFIX + "/foo/revision/-1")
                                                    .aggregate().join();
        assertThatJson(res.contentUtf8()).isEqualTo("{\"revision\":1}");
    }
}
