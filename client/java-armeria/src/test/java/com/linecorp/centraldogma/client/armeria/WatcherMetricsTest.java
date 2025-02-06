/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.client.armeria;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.client.Watcher;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class WatcherMetricsTest {
    private static final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private static final String project = "project";
    private static final String repo = "repo";

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configureClient(ArmeriaCentralDogmaBuilder builder) {
            builder.meterRegistry(meterRegistry);
        }

        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject(project).join();
            client.createRepository(project, repo).join();
        }
    };

    @Test
    void before() {
        meterRegistry.clear();
    }

    @Test
    void queryOfText() throws Exception {
        final CentralDogmaRepository dogmaRepo = dogma.client().forRepo(project, repo);
        final PushResult hoge1stResult = dogmaRepo.commit("Add hoge.txt", Change.ofTextUpsert("/hoge.txt", "1"))
                                                  .push().join();

        final Watcher<String> watcher = dogmaRepo.watcher(Query.ofText("/hoge.txt")).start();

        // Metrics will not be emitted until the values are ready.
        assertThat(latestRevisionGauges(tags(watcher, "/hoge.txt")).size()).isEqualTo(0);
        assertThat(latestCommitTimeGauges(tags(watcher, "/hoge.txt")).size()).isEqualTo(0);

        // Metrics will be emitted once the values are ready.
        watcher.awaitInitialValue();
        await().untilAsserted(() -> {
            final List<Gauge> latestRevisionGauges = latestRevisionGauges(tags(watcher, "/hoge.txt"));
            final List<Gauge> latestCommitTimeGauges = latestCommitTimeGauges(tags(watcher, "/hoge.txt"));
            assertThat(latestRevisionGauges.size()).isEqualTo(1);
            assertThat(latestRevisionGauges.get(0).value()).isEqualTo(hoge1stResult.revision().major());
            assertThat(latestCommitTimeGauges.size()).isEqualTo(1);
            assertThat(latestCommitTimeGauges.get(0).value()).isEqualTo(hoge1stResult.when());
        });

        // When a commit is added, the metrics will also be updated.
        final PushResult hoge2ndResult = dogmaRepo.commit("Add hoge.txt", Change.ofTextUpsert("/hoge.txt", "2"))
                                                  .push().join();
        await().untilAsserted(() -> {
            final List<Gauge> latestRevisionGauges = latestRevisionGauges(tags(watcher, "/hoge.txt"));
            final List<Gauge> latestCommitTimeGauges = latestCommitTimeGauges(tags(watcher, "/hoge.txt"));
            assertThat(latestRevisionGauges.size()).isEqualTo(1);
            assertThat(latestRevisionGauges.get(0).value()).isEqualTo(hoge2ndResult.revision().major());
            assertThat(latestCommitTimeGauges.size()).isEqualTo(1);
            assertThat(latestCommitTimeGauges.get(0).value()).isEqualTo(hoge2ndResult.when());
            assertThat(hoge2ndResult.revision().major()).isGreaterThan(hoge1stResult.revision().major());
            assertThat(hoge2ndResult.when()).isGreaterThanOrEqualTo(hoge1stResult.when());
        });

        // When a commit is added, the metrics will also be updated.
        watcher.close();
        final List<Gauge> latestRevisionGauges = latestRevisionGauges(tags(watcher, "/hoge.txt"));
        final List<Gauge> latestCommitTimeGauges = latestCommitTimeGauges(tags(watcher, "/hoge.txt"));
        assertThat(latestRevisionGauges.size()).isEqualTo(0);
        assertThat(latestCommitTimeGauges.size()).isEqualTo(0);
    }

    @Test
    void pathPatternAll() throws Exception {
        final CentralDogmaRepository dogmaRepo = dogma.client().forRepo(project, repo);
        final PushResult fooResult = dogmaRepo.commit("Add foo.txt", Change.ofTextUpsert("/foo.txt", "1"))
                                              .push().join();

        final Watcher<Revision> watcher = dogmaRepo.watcher(PathPattern.all()).start();

        // Metrics will not be emitted until the values are ready.
        assertThat(latestRevisionGauges(tags(watcher, "/**")).size()).isEqualTo(0);
        assertThat(latestCommitTimeGauges(tags(watcher, "/**")).size()).isEqualTo(0);

        // Metrics will be emitted once the values are ready.
        watcher.awaitInitialValue();
        await().untilAsserted(() -> {
            final List<Gauge> latestRevisionGauges = latestRevisionGauges(tags(watcher, "/**"));
            final List<Gauge> latestCommitTimeGauges = latestCommitTimeGauges(tags(watcher, "/**"));
            assertThat(latestRevisionGauges.size()).isEqualTo(1);
            assertThat(latestRevisionGauges.get(0).value()).isEqualTo(fooResult.revision().major());
            assertThat(latestCommitTimeGauges.size()).isEqualTo(1);
            assertThat(latestCommitTimeGauges.get(0).value()).isEqualTo(fooResult.when());
        });

        // When a commit is added, the metrics will also be updated.
        final PushResult barResult = dogmaRepo.commit("Add bar.txt", Change.ofTextUpsert("/bar.txt", "1"))
                                              .push().join();
        await().untilAsserted(() -> {
            final List<Gauge> latestRevisionGauges = latestRevisionGauges(tags(watcher, "/**"));
            final List<Gauge> latestCommitTimeGauges = latestCommitTimeGauges(tags(watcher, "/**"));
            assertThat(latestRevisionGauges.size()).isEqualTo(1);
            assertThat(latestRevisionGauges.get(0).value()).isEqualTo(barResult.revision().major());
            assertThat(latestCommitTimeGauges.size()).isEqualTo(1);
            assertThat(latestCommitTimeGauges.get(0).value()).isEqualTo(barResult.when());
            assertThat(barResult.revision().major()).isGreaterThan(fooResult.revision().major());
            assertThat(barResult.when()).isGreaterThanOrEqualTo(fooResult.when());
        });

        // When a commit is added, the metrics will also be updated.
        watcher.close();
        final List<Gauge> latestRevisionGauges = latestRevisionGauges(tags(watcher, "/**"));
        final List<Gauge> latestCommitTimeGauges = latestCommitTimeGauges(tags(watcher, "/**"));
        assertThat(latestRevisionGauges.size()).isEqualTo(0);
        assertThat(latestCommitTimeGauges.size()).isEqualTo(0);
    }

    private static List<Gauge> latestRevisionGauges(Tags tags) {
        return new ArrayList<>(
                meterRegistry
                        .find("centraldogma.watcher.latest.revision")
                        .tags(tags)
                        .gauges()
        );
    }

    private static List<Gauge> latestCommitTimeGauges(Tags tags) {
        return new ArrayList<>(
                meterRegistry
                        .find("centraldogma.watcher.latest.commit.time")
                        .tags(tags)
                        .gauges()
        );
    }

    private static Tags tags(Watcher<?> watcher, String pathPattern) {
        return Tags.of(
                "project", project,
                "repository", repo,
                "pathPattern", pathPattern,
                "watcher_hash", String.valueOf(System.identityHashCode(watcher))
        );
    }
}
