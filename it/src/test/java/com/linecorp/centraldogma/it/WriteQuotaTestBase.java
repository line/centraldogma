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

package com.linecorp.centraldogma.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.QuotaConfig;
import com.linecorp.centraldogma.server.metadata.ProjectMetadata;

abstract class WriteQuotaTestBase {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String projectName = "test_prj";

    protected abstract WebClient webClient();

    protected abstract CentralDogma dogmaClient();

    @Test
    void updateWriteQuota() throws Exception {
        final String repositoryName = "test_repo";
        dogmaClient().createProject(projectName).join();
        dogmaClient().createRepository(projectName, repositoryName).join();

        // check default write quota
        final List<CompletableFuture<PushResult>> futures = parallelPush(dogmaClient(), repositoryName, 5);
        CompletableFutures.allAsList(futures).join();

        /// update write quota to 2qps
        QuotaConfig writeQuota = new QuotaConfig(2, 1);
        QuotaConfig updated = updateWriteQuota(webClient(), repositoryName, writeQuota);
        assertThat(updated).isEqualTo(writeQuota);

        // Wait for releasing previously acquired locks
        Thread.sleep(1000);

        final List<CompletableFuture<PushResult>> futures2 = parallelPush(dogmaClient(), repositoryName, 2);
        assertThat(CompletableFutures.allAsList(futures2).join()).hasSize(4);

        // Exceed the quota
        final List<CompletableFuture<PushResult>> futures3 = parallelPush(dogmaClient(), repositoryName, 4);
        assertThatThrownBy(() -> CompletableFutures.allAsList(futures3).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CentralDogmaException.class)
                .hasMessageContaining(
                        "Too many commits are sent to '/test_prj/test_repo' (quota limit: 2.0/sec)");

        // Wait for releasing previously acquired locks
        Thread.sleep(1000);

        // Increase write quota
        writeQuota = new QuotaConfig(5, 1);
        updated = updateWriteQuota(webClient(), repositoryName, writeQuota);
        assertThat(updated).isEqualTo(writeQuota);

        final List<CompletableFuture<PushResult>> futures4 = parallelPush(dogmaClient(), repositoryName, 5);
        assertThat(CompletableFutures.allAsList(futures4).join()).hasSize(10);
    }

    private static QuotaConfig updateWriteQuota(WebClient adminClient, String repoName, QuotaConfig writeQuota)
            throws JsonProcessingException {
        final String updatePath = "/api/v1/metadata/test_prj/repos/" + repoName + "/quota/write";
        final String content = mapper.writeValueAsString(writeQuota);
        final HttpRequest req = HttpRequest.of(HttpMethod.PATCH, updatePath, MediaType.JSON_PATCH, content);
        assertThat(adminClient.execute(req).aggregate().join().status()).isEqualTo(HttpStatus.OK);

        final AggregatedHttpResponse res = adminClient.get("/api/v1/projects/test_prj").aggregate().join();
        final ProjectMetadata meta = Jackson.readValue(res.contentUtf8(), ProjectMetadata.class);
        return meta.repo(repoName).writeQuota();
    }

    private static List<CompletableFuture<PushResult>> parallelPush(CentralDogma dogmaClient, String repoName,
                                                                    int concurrency)
            throws InterruptedException {
        final int iteration = concurrency * 2;
        final ImmutableList.Builder<CompletableFuture<PushResult>> builder =
                ImmutableList.builderWithExpectedSize(iteration);
        final int sleep = 1000 / concurrency + 50;
        for (int i = 0; i < iteration; i++) {
            builder.add(dogmaClient.push(projectName, repoName, Revision.HEAD, i + ". test commit",
                                         Change.ofTextUpsert("/foo.txt", "Hello CentralDogma! " + i)));
            Thread.sleep(sleep);
        }
        return builder.build();
    }
}
