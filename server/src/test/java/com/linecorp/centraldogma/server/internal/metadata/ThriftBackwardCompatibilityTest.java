/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.metadata;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.PROJECTS_PREFIX;
import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.REPOS;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class ThriftBackwardCompatibilityTest {

    @ClassRule
    public static final CentralDogmaRule dogma = new CentralDogmaRule();

    private static HttpClient httpClient;

    private static final String projectName = "foo";

    @BeforeClass
    public static void init() {
        final InetSocketAddress serverAddress = dogma.dogma().activePort().get().localAddress();
        final String serverUri = "http://" + serverAddress.getHostString() + ':' + serverAddress.getPort();
        httpClient = new HttpClientBuilder(serverUri)
                .addHttpHeader(HttpHeaderNames.AUTHORIZATION, "bearer anonymous").build();
    }

    @Test
    public void shouldBackwardCompatible() throws Exception {
        final String repo1 = "repo1";

        dogma.client().createProject(projectName).join();
        dogma.client().createRepository(projectName, repo1).join();
        dogma.client().push(projectName, repo1, Revision.HEAD, "",
                            Change.ofTextUpsert("/sample.txt", "test")).join();

        AggregatedHttpMessage res;
        res = httpClient.get(PROJECTS_PREFIX + '/' + projectName + REPOS).aggregate().join();
        final List<JsonNode> nodes = new ArrayList<>();
        Jackson.readTree(res.content().toStringUtf8()).elements().forEachRemaining(nodes::add);

        assertThat(nodes.stream().map(n -> n.get("name").textValue()).collect(Collectors.toList()))
                .containsAnyOf("dogma", "meta", repo1);

        ProjectMetadata metadata;

        res = httpClient.get(PROJECTS_PREFIX + '/' + projectName).aggregate().join();
        metadata = Jackson.readValue(res.content().toStringUtf8(), ProjectMetadata.class);
        assertThat(metadata.repos().size()).isOne();
        assertThat(metadata.repo(repo1)).isNotNull();
        assertThat(metadata.repo(repo1).removal()).isNull();

        dogma.client().removeRepository(projectName, repo1).join();

        res = httpClient.get(PROJECTS_PREFIX + '/' + projectName).aggregate().join();
        metadata = Jackson.readValue(res.content().toStringUtf8(), ProjectMetadata.class);
        assertThat(metadata.repos().size()).isOne();
        assertThat(metadata.repo(repo1)).isNotNull();
        assertThat(metadata.repo(repo1).removal()).isNotNull();

        dogma.client().unremoveRepository(projectName, repo1).join();

        res = httpClient.get(PROJECTS_PREFIX + '/' + projectName).aggregate().join();
        metadata = Jackson.readValue(res.content().toStringUtf8(), ProjectMetadata.class);
        assertThat(metadata.repos().size()).isOne();
        assertThat(metadata.repo(repo1)).isNotNull();
        assertThat(metadata.repo(repo1).removal()).isNull();
    }
}
