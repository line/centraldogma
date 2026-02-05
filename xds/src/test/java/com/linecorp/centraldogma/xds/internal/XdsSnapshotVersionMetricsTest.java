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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class XdsSnapshotVersionMetricsTest {

    private static final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.meterRegistry(meterRegistry);
        }
    };

    @Test
    void snapshotVersionMetrics() throws Exception {
        final WebClient webClient = dogma.httpClient();

        final int initialClusterVersion = getRevisionFromCounter(meterRegistry, "cluster");
        final int initialEndpointVersion = getRevisionFromCounter(meterRegistry, "endpoint");
        final int initialListenerVersion = getRevisionFromCounter(meterRegistry, "listener");
        final int initialRouteVersion = getRevisionFromCounter(meterRegistry, "route");
        final int initialSecretVersion = getRevisionFromCounter(meterRegistry, "secret");

        final int isClusterInitialized = isResourceInitialized(meterRegistry, "cluster");
        final int isEndpointInitialized = isResourceInitialized(meterRegistry, "endpoint");
        final int isListenerInitialized = isResourceInitialized(meterRegistry, "listener");
        final int isRouteInitialized = isResourceInitialized(meterRegistry, "route");
        final int isSecretInitialized = isResourceInitialized(meterRegistry, "secret");

        assertThat(initialClusterVersion).isEqualTo(1);
        assertThat(initialEndpointVersion).isEqualTo(1);
        assertThat(initialListenerVersion).isEqualTo(1);
        assertThat(initialRouteVersion).isEqualTo(1);
        assertThat(initialSecretVersion).isEqualTo(1);

        // Resources are not initialized until non-"empty_resources" are created
        assertThat(isClusterInitialized).isEqualTo(0);
        assertThat(isEndpointInitialized).isEqualTo(0);
        assertThat(isListenerInitialized).isEqualTo(0);
        assertThat(isRouteInitialized).isEqualTo(0);
        assertThat(isSecretInitialized).isEqualTo(0);

        // Create a group and cluster
        createGroup("metrics-test", webClient);
        final String clusterName = "groups/metrics-test/clusters/test-cluster";
        final Cluster cluster1 = cluster(clusterName, 1);
        createCluster("groups/metrics-test", "test-cluster", cluster1, webClient);

        // Version should change after adding a cluster
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            final int newRevision = getRevisionFromCounter(meterRegistry, "cluster");
            assertThat(newRevision).isEqualTo(2);
            assertThat(isResourceInitialized(meterRegistry, "cluster")).isEqualTo(1);
        });

        // Update the cluster
        final Cluster cluster2 = cluster(clusterName, 2);
        updateCluster("groups/metrics-test", "test-cluster", cluster2, webClient);

        // Version should change after updating the cluster
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            final int newVersion = getRevisionFromCounter(meterRegistry, "cluster");
            assertThat(newVersion).isEqualTo(3);
            assertThat(isResourceInitialized(meterRegistry, "cluster")).isEqualTo(1);
        });
    }

    private static int getRevisionFromCounter(MeterRegistry meterRegistry, String resourceType) {
        final Counter counter = meterRegistry.find("xds.control.plane.snapshot.revision")
                                             .tag("resource", resourceType)
                                             .counter();
        return (int) counter.count();
    }

    private static int isResourceInitialized(MeterRegistry meterRegistry, String resourceType) {
        final Gauge counter = meterRegistry.find("xds.control.plane.snapshot.initialized")
                                           .tag("resource", resourceType)
                                           .gauge();
        return (int) counter.value();
    }
}
