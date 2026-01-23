/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.centraldogma.xds.internal;

import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.cluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createCluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.updateCluster;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

class XdsSnapshotVersionMetricsTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @Test
    void snapshotVersionMetrics() throws Exception {
        final MeterRegistry meterRegistry = dogma.dogma().meterRegistry().orElseThrow();
        final WebClient webClient = dogma.httpClient();

        // Initial empty version metrics should be registered with "empty_resources" version
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertVersionGaugeExists(meterRegistry, "cluster");
            assertVersionGaugeExists(meterRegistry, "endpoint");
            assertVersionGaugeExists(meterRegistry, "listener");
            assertVersionGaugeExists(meterRegistry, "route");
            assertVersionGaugeExists(meterRegistry, "secret");
        });

        final String initialClusterVersion = getVersionFromGauge(meterRegistry, "cluster");
        final String initialEndpointVersion = getVersionFromGauge(meterRegistry, "endpoint");
        final String initialListenerVersion = getVersionFromGauge(meterRegistry, "listener");
        final String initialRouteVersion = getVersionFromGauge(meterRegistry, "route");
        final String initialSecretVersion = getVersionFromGauge(meterRegistry, "secret");

        // All initial versions should be "empty_resources"
        assertThat(initialClusterVersion).isEqualTo("empty_resources");
        assertThat(initialEndpointVersion).isEqualTo("empty_resources");
        assertThat(initialListenerVersion).isEqualTo("empty_resources");
        assertThat(initialRouteVersion).isEqualTo("empty_resources");
        assertThat(initialSecretVersion).isEqualTo("empty_resources");

        // Create a group and cluster
        createGroup("metrics-test", webClient);
        final String clusterName = "groups/metrics-test/clusters/test-cluster";
        final Cluster cluster1 = cluster(clusterName, 1);
        createCluster("groups/metrics-test", "test-cluster", cluster1, webClient);

        // Version should change after adding a cluster
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            final String newVersion = getVersionFromGauge(meterRegistry, "cluster");
            assertThat(newVersion).isNotEqualTo(initialClusterVersion);
            // Version should be a SHA-256 hash (64 characters)
            assertThat(newVersion.length()).isEqualTo(64);
        });

        final String versionAfterCreate = getVersionFromGauge(meterRegistry, "cluster");

        // Update the cluster
        final Cluster cluster2 = cluster(clusterName, 2);
        updateCluster("groups/metrics-test", "test-cluster", cluster2, webClient);

        // Version should change after updating the cluster
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            final String newVersion = getVersionFromGauge(meterRegistry, "cluster");
            assertThat(newVersion).isNotEqualTo(versionAfterCreate);
        });

        // Verify gauge value is always 1 (info gauge pattern)
        final Collection<Gauge> clusterGauges = meterRegistry.find("xds.control.plane.snapshot.version")
                                                             .tag("resource_type", "cluster")
                                                             .gauges();
        assertThat(clusterGauges).hasSize(1);
        assertThat(clusterGauges.iterator().next().value()).isEqualTo(1.0);
    }

    private static void assertVersionGaugeExists(MeterRegistry meterRegistry, String resourceType) {
        final Collection<Gauge> gauges = meterRegistry.find("xds.control.plane.snapshot.version")
                                                      .tag("resource_type", resourceType)
                                                      .gauges();
        assertThat(gauges).hasSize(1);
    }

    private static String getVersionFromGauge(MeterRegistry meterRegistry, String resourceType) {
        final Collection<Gauge> gauges = meterRegistry.find("xds.control.plane.snapshot.version")
                                                      .tag("resource_type", resourceType)
                                                      .gauges();
        assertThat(gauges).hasSize(1);
        return gauges.iterator().next().getId().getTag("version");
    }
}
