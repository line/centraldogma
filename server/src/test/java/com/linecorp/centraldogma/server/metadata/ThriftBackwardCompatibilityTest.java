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

package com.linecorp.centraldogma.server.metadata;

import static com.linecorp.centraldogma.common.Author.SYSTEM;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.REPOS;
import static com.linecorp.centraldogma.server.storage.project.Project.REPO_DOGMA;
import static com.linecorp.centraldogma.server.storage.project.Project.REPO_META;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.centraldogma.internal.CsrfToken;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.thrift.Author;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaService.Iface;
import com.linecorp.centraldogma.internal.thrift.Change;
import com.linecorp.centraldogma.internal.thrift.ChangeType;
import com.linecorp.centraldogma.internal.thrift.Comment;
import com.linecorp.centraldogma.internal.thrift.Revision;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class ThriftBackwardCompatibilityTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    private static WebClient webClient;
    private static Iface client;

    private static final Revision head = new Revision(-1, 0);
    private static final Author author = new Author(SYSTEM.name(), SYSTEM.email());

    private static final String projectName = "foo";

    @BeforeAll
    static void init() {
        final InetSocketAddress serverAddress = dogma.dogma().activePort().localAddress();
        webClient = WebClient.builder("http://127.0.0.1:" + serverAddress.getPort())
                             .addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer " + CsrfToken.ANONYMOUS)
                             .build();

        client = Clients.builder("ttext+http", Endpoint.of("127.0.0.1", serverAddress.getPort()),
                                 "/cd/thrift/v1")
                        .setHeader(HttpHeaderNames.AUTHORIZATION, "Bearer " + CsrfToken.ANONYMOUS)
                        .build(Iface.class);
    }

    @Test
    void shouldBackwardCompatible() throws Exception {
        final String repo1 = "repo1";

        client.createProject(projectName);
        client.createRepository(projectName, repo1);
        client.push(projectName, repo1, head, author, "", new Comment("comment"),
                    ImmutableList.of(new Change("/sample.txt", ChangeType.UPSERT_TEXT).setContent("test")));

        AggregatedHttpResponse res;
        res = webClient.get(PROJECTS_PREFIX + '/' + projectName + REPOS).aggregate().join();
        final List<JsonNode> nodes = new ArrayList<>();
        Jackson.readTree(res.contentUtf8()).elements().forEachRemaining(nodes::add);

        assertThat(nodes.stream().map(n -> n.get("name").textValue()).collect(Collectors.toList()))
                .containsAnyOf(REPO_DOGMA, REPO_META, repo1);

        ProjectMetadata metadata;

        res = webClient.get(PROJECTS_PREFIX + '/' + projectName).aggregate().join();
        metadata = Jackson.readValue(res.contentUtf8(), ProjectMetadata.class);
        assertThat(metadata.repos()).hasSize(2);
        assertThat(metadata.repo(repo1)).isNotNull();
        assertThat(metadata.repo(repo1).removal()).isNull();
        assertThat(metadata.repo(REPO_META)).isNotNull();
        assertThat(metadata.repo(REPO_META).removal()).isNull();

        client.removeRepository(projectName, repo1);

        res = webClient.get(PROJECTS_PREFIX + '/' + projectName).aggregate().join();
        metadata = Jackson.readValue(res.contentUtf8(), ProjectMetadata.class);
        assertThat(metadata.repos()).hasSize(2);
        assertThat(metadata.repo(repo1)).isNotNull();
        assertThat(metadata.repo(repo1).removal()).isNotNull();
        assertThat(metadata.repo(REPO_META)).isNotNull();
        assertThat(metadata.repo(REPO_META).removal()).isNull();

        client.unremoveRepository(projectName, repo1);

        res = webClient.get(PROJECTS_PREFIX + '/' + projectName).aggregate().join();
        metadata = Jackson.readValue(res.contentUtf8(), ProjectMetadata.class);
        assertThat(metadata.repos()).hasSize(2);
        assertThat(metadata.repo(repo1)).isNotNull();
        assertThat(metadata.repo(repo1).removal()).isNull();
        assertThat(metadata.repo(REPO_META)).isNotNull();
        assertThat(metadata.repo(REPO_META).removal()).isNull();
    }
}
