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
import io.micrometer.core.instrument.TimeGauge;
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

    @RegisterExtension
    static final CentralDogmaExtension dogmaWithoutMetrics = new CentralDogmaExtension() {
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

        // The initial value of the metrics is -1
        final List<Gauge> latestRevisionGauges1 = latestRevisionGauges(tags("/hoge.txt"));
        final List<TimeGauge> latestReceivedTimeGauges1 = latestReceivedTimeGauges(tags("/hoge.txt"));
        assertThat(latestRevisionGauges1.size()).isEqualTo(1);
        assertThat(latestRevisionGauges1.get(0).value()).isEqualTo(-1);
        assertThat(latestReceivedTimeGauges1.size()).isEqualTo(1);
        assertThat(latestReceivedTimeGauges1.get(0).value()).isEqualTo(-1);

        // Metrics will be emitted once the values are ready.
        watcher.awaitInitialValue();
        await().untilAsserted(() -> {
            final List<Gauge> latestRevisionGauges2 = latestRevisionGauges(tags("/hoge.txt"));
            final List<TimeGauge> latestReceivedTimeGauges2 = latestReceivedTimeGauges(tags("/hoge.txt"));
            assertThat(latestRevisionGauges2.size()).isEqualTo(1);
            assertThat(latestRevisionGauges2.get(0).value()).isEqualTo(hoge1stResult.revision().major());
            assertThat(latestReceivedTimeGauges2.size()).isEqualTo(1);
            assertThat(latestReceivedTimeGauges2.get(0).value())
                    .isGreaterThanOrEqualTo(hoge1stResult.when() / 1000.0);
        });

        // When a commit is added, the metrics will also be updated.
        final PushResult hoge2ndResult = dogmaRepo.commit("Add hoge.txt", Change.ofTextUpsert("/hoge.txt", "2"))
                                                  .push().join();
        await().untilAsserted(() -> {
            final List<Gauge> latestRevisionGauges3 = latestRevisionGauges(tags("/hoge.txt"));
            final List<TimeGauge> latestReceivedTimeGauges3 = latestReceivedTimeGauges(tags("/hoge.txt"));
            assertThat(latestRevisionGauges3.size()).isEqualTo(1);
            assertThat(latestRevisionGauges3.get(0).value()).isEqualTo(hoge2ndResult.revision().major());
            assertThat(latestReceivedTimeGauges3.size()).isEqualTo(1);
            assertThat(latestReceivedTimeGauges3.get(0).value())
                    .isGreaterThanOrEqualTo(hoge2ndResult.when() / 1000.0);
            assertThat(hoge2ndResult.revision().major()).isGreaterThan(hoge1stResult.revision().major());
            assertThat(hoge2ndResult.when()).isGreaterThanOrEqualTo(hoge1stResult.when());
        });

        // When a commit is added, the metrics will also be updated.
        watcher.close();
        final List<Gauge> latestRevisionGauges4 = latestRevisionGauges(tags("/hoge.txt"));
        final List<TimeGauge> latestReceivedTimeGauges4 = latestReceivedTimeGauges(tags("/hoge.txt"));
        assertThat(latestRevisionGauges4.size()).isEqualTo(0);
        assertThat(latestReceivedTimeGauges4.size()).isEqualTo(0);
    }

    @Test
    void pathPatternAll() throws Exception {
        final CentralDogmaRepository dogmaRepo = dogma.client().forRepo(project, repo);
        final PushResult fooResult = dogmaRepo.commit("Add foo.txt", Change.ofTextUpsert("/foo.txt", "1"))
                                              .push().join();

        final Watcher<Revision> watcher = dogmaRepo.watcher(PathPattern.all()).start();

        // The initial value of the metrics is -1
        final List<Gauge> latestRevisionGauges1 = latestRevisionGauges(tags("/**"));
        final List<TimeGauge> latestReceivedTimeGauges1 = latestReceivedTimeGauges(tags("/**"));
        assertThat(latestRevisionGauges1.size()).isEqualTo(1);
        assertThat(latestRevisionGauges1.get(0).value()).isEqualTo(-1);
        assertThat(latestReceivedTimeGauges1.size()).isEqualTo(1);
        assertThat(latestReceivedTimeGauges1.get(0).value()).isEqualTo(-1);

        // Metrics will be emitted once the values are ready.
        watcher.awaitInitialValue();
        await().untilAsserted(() -> {
            final List<Gauge> latestRevisionGauges2 = latestRevisionGauges(tags("/**"));
            final List<TimeGauge> latestReceivedTimeGauges2 = latestReceivedTimeGauges(tags("/**"));
            assertThat(latestRevisionGauges2.size()).isEqualTo(1);
            assertThat(latestRevisionGauges2.get(0).value()).isEqualTo(fooResult.revision().major());
            assertThat(latestReceivedTimeGauges2.size()).isEqualTo(1);
            assertThat(latestReceivedTimeGauges2.get(0).value())
                    .isGreaterThanOrEqualTo(fooResult.when() / 1000.0);
        });

        // When a commit is added, the metrics will also be updated.
        final PushResult barResult = dogmaRepo.commit("Add bar.txt", Change.ofTextUpsert("/bar.txt", "1"))
                                              .push().join();
        await().untilAsserted(() -> {
            final List<Gauge> latestRevisionGauges3 = latestRevisionGauges(tags("/**"));
            final List<TimeGauge> latestReceivedTimeGauges3 = latestReceivedTimeGauges(tags("/**"));
            assertThat(latestRevisionGauges3.size()).isEqualTo(1);
            assertThat(latestRevisionGauges3.get(0).value()).isEqualTo(barResult.revision().major());
            assertThat(latestReceivedTimeGauges3.size()).isEqualTo(1);
            assertThat(latestReceivedTimeGauges3.get(0).value())
                    .isGreaterThanOrEqualTo(barResult.when() / 1000.0);
            assertThat(barResult.revision().major()).isGreaterThan(fooResult.revision().major());
            assertThat(barResult.when()).isGreaterThanOrEqualTo(fooResult.when());
        });

        // When a commit is added, the metrics will also be updated.
        watcher.close();
        final List<Gauge> latestRevisionGauges4 = latestRevisionGauges(tags("/**"));
        final List<TimeGauge> latestReceivedTimeGauges4 = latestReceivedTimeGauges(tags("/**"));
        assertThat(latestRevisionGauges4.size()).isEqualTo(0);
        assertThat(latestReceivedTimeGauges4.size()).isEqualTo(0);
    }

    @Test
    void noMetrics() throws Exception {
        final CentralDogmaRepository dogmaRepo = dogmaWithoutMetrics.client().forRepo(project, repo);
        dogmaRepo.commit("Add hoge.txt", Change.ofTextUpsert("/hoge.txt", "1")).push().join();

        final Watcher<String> watcher = dogmaRepo.watcher(Query.ofText("/hoge.txt")).start();

        // If `MeterRegistry` is not set, metrics will not be emitted.
        watcher.awaitInitialValue();
        Thread.sleep(1000); // wait updateLatestCommitAsync
        final List<Gauge> latestRevisionGauges = latestRevisionGauges(tags("/hoge.txt"));
        final List<TimeGauge> latestReceivedTimeGauges = latestReceivedTimeGauges(tags("/hoge.txt"));
        assertThat(latestRevisionGauges.size()).isEqualTo(0);
        assertThat(latestReceivedTimeGauges.size()).isEqualTo(0);
    }

    private static List<Gauge> latestRevisionGauges(Tags tags) {
        return new ArrayList<>(meterRegistry.find("centraldogma.client.watcher.latest.revision").tags(tags)
                                            .gauges());
    }

    private static List<TimeGauge> latestReceivedTimeGauges(Tags tags) {
        return new ArrayList<>(meterRegistry.find("centraldogma.client.watcher.latest.received.time")
                                            .tags(tags)
                                            .timeGauges());
    }

    private static Tags tags(String pathPattern) {
        return Tags.of(
                "project", project,
                "repository", repo,
                "path", pathPattern
        );
    }
}
