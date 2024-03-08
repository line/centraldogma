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

package com.linecorp.centraldogma.server.internal.admin.service;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_VO_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.REPOS;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.REPOSITORIES;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.BeforeAll;
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
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class RepositoryServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.webAppEnabled(true);
        }

        @Override
        protected void configureHttpClient(WebClientBuilder builder) {
            builder.addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous");
        }
    };

    private static final String REPOS_PREFIX = PROJECTS_PREFIX + "/myPro" + REPOS;
    private static final String REPOSITORIES_PREFIX = PROJECTS_VO_PREFIX + "/myPro" + REPOSITORIES;
    private static final String FILES_PATH = "/files/revisions/head";

    @BeforeAll
    static void setUp() {
        createProject(dogma);
    }

    @Test
    void getHistory() throws IOException {
        final WebClient client = dogma.httpClient();
        final String repoName = "myRepo";
        final AggregatedHttpResponse myRepoCreatedResponse = createRepository(client, repoName);
        final ResponseHeaders repoResponseHeaders = ResponseHeaders.of(myRepoCreatedResponse.headers());
        assertThat(repoResponseHeaders.status()).isEqualTo(HttpStatus.CREATED);

        final String repoNamePrefix = "/myRepo";
        final AggregatedHttpResponse barCreatedResponse = createFile(client,
                repoNamePrefix,
                "/foo/bar.txt",
                "bar.txt",
                "Bar");
        final ResponseHeaders fileResponseHeader1 = ResponseHeaders.of(barCreatedResponse.headers());
        assertThat(fileResponseHeader1.status()).isEqualTo(HttpStatus.CREATED);

        final AggregatedHttpResponse bar2Created = createFile(client,
                repoNamePrefix,
                "/foo2/bar2.txt",
                "bar2.txt",
                "Bar 2");
        final ResponseHeaders file2ResponseHeaders = ResponseHeaders.of(bar2Created.headers());
        assertThat(file2ResponseHeaders.status()).isEqualTo(HttpStatus.CREATED);

        final AggregatedHttpResponse myRepoHistoryResponse = getHistory(client, repoNamePrefix, "/");
        final JsonNode myRepoHistory = Jackson.readTree(myRepoHistoryResponse.contentUtf8());
        assertThat(myRepoHistory.size()).isEqualTo(3);
        assertThat(myRepoHistory.get(0).get("summary").asText()).isEqualTo("Add /foo2/bar2.txt");
        assertThat(myRepoHistory.get(1).get("summary").asText()).isEqualTo("Add /foo/bar.txt");
        assertThat(myRepoHistory.get(2).get("summary").asText()).isEqualTo("Create a new repository");

        final AggregatedHttpResponse fooHistoryResponse = getHistory(client, repoNamePrefix, "/foo");
        final JsonNode fooHistory = Jackson.readTree(fooHistoryResponse.contentUtf8());
        assertThat(fooHistory.get(0).get("summary").asText()).isEqualTo("Add /foo/bar.txt");
        final boolean containsUndesiredString = StreamSupport.stream(fooHistory.spliterator(), false)
                .anyMatch(node -> "Add /foo2/bar2.txt".equals(node.get("summary").asText()));
        assertThat(containsUndesiredString).isFalse();

        final AggregatedHttpResponse foo2HistoryResponse = getHistory(client, repoNamePrefix, "/foo2");
        final JsonNode foo2History = Jackson.readTree(foo2HistoryResponse.contentUtf8());
        assertThat(foo2History.get(0).get("summary").asText()).isEqualTo("Add /foo2/bar2.txt");
    }

    private static AggregatedHttpResponse getHistory(WebClient client, String repoName, String childRepoName) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET,
                REPOSITORIES_PREFIX + repoName + "/history" + childRepoName + "?from=head&to=1",
                HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        return client.execute(headers).aggregate().join();
    }

    private static AggregatedHttpResponse createRepository(WebClient client, String repoName) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, REPOS_PREFIX,
                HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final String body = "{\"name\": \"" + repoName + "\"}";
        return client.execute(headers, body).aggregate().join();
    }

    private static AggregatedHttpResponse createFile(WebClient client,
                                                     String repoName,
                                                     String path,
                                                     String fileName,
                                                     String content) {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST,
                REPOSITORIES_PREFIX + repoName + FILES_PATH,
                HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final String body = "{\n" +
                "  \"file\": {\n" +
                "    \"name\": \"" + fileName + "\",\n" +
                "    \"type\": \"TEXT\",\n" +
                "    \"content\": \"" + content + "\",\n" +
                "    \"path\": \"" + path + "\"\n" +
                "  },\n" +
                "  \"commitMessage\": {\n" +
                "    \"summary\": \"Add " + path + "\",\n" +
                "    \"detail\": {\n" +
                "      \"content\": \"\",\n" +
                "      \"markup\": \"PLAINTEXT\"\n" +
                "    }\n" +
                "  }\n" +
                '}';
        return client.execute(headers, body).aggregate().join();
    }

    private static void createProject(CentralDogmaExtension dogma) {
        final String body = "{\"name\": \"myPro\"}";
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, PROJECTS_PREFIX,
                HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        final WebClient client = dogma.httpClient();
        client.execute(headers, body).aggregate().join();
    }
}
