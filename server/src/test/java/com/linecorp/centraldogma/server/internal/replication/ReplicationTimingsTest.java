/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.server.internal.replication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.internal.CentralDogmaReplicationExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ReplicationTimingsTest {

    private static final Map<Integer, SimpleMeterRegistry> meterRegistryMap = new HashMap<>();

    @RegisterExtension
    static CentralDogmaReplicationExtension replica = new CentralDogmaReplicationExtension(3) {
        @Override
        protected void configureEach(int serverId, CentralDogmaBuilder builder) {
            final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            meterRegistryMap.put(serverId, meterRegistry);
            builder.meterRegistry(meterRegistry);
        }
    };

    @Test
    void pushAndRecordMetrics() {
        final CentralDogma client0 = replica.servers().get(0).client();
        client0.createProject("fooProject").join();
        final CentralDogmaRepository repo = client0.createRepository("fooProject", "barRepo").join();
        repo.commit("Test", Change.ofTextUpsert("/hello.txt", "Hello, World!"))
            .push().join();

        final SimpleMeterRegistry meterRegistry0 = meterRegistryMap.get(1);
        await().untilAsserted(() -> {
            final Map<String, Double> metrics = MoreMeters.measureAll(meterRegistry0);
            assertMetricExists(metrics, "replication.executor.queue.latency.percentile#value");
            assertMetricExists(metrics, "replication.lock.acquisition.percentile#value{acquired=");
            assertMetricExists(metrics, "replication.lock.release.percentile#value");
            assertMetricExists(metrics, "replication.command.execution.percentile#value");
            assertMetricExists(metrics, "replication.log.replay.percentile#value");
            assertMetricExists(metrics, "replication.log.store.percentile#value");
        });

        final SimpleMeterRegistry meterRegistry1 = meterRegistryMap.get(2);
        final Map<String, Double> metrics1 = MoreMeters.measureAll(meterRegistry1);
        assertThat(metrics1).allSatisfy((key, value) -> {
            assertThat(key).doesNotStartWith("replication.");
        });
        final SimpleMeterRegistry meterRegistry2 = meterRegistryMap.get(3);
        final Map<String, Double> metrics2 = MoreMeters.measureAll(meterRegistry2);

        assertThat(metrics2).allSatisfy((key, value) -> {
            assertThat(key).doesNotStartWith("replication.");
        });
    }

    private static void assertMetricExists(Map<String, Double> metrics, String metricName) {
        assertThat(metrics).anySatisfy((name, value) -> {
            assertThat(name).startsWith(metricName);
        });
    }
}
