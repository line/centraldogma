/*
 * Copyright 2023 LINE Corporation
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

import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.CONFIG_SOURCE_CLUSTER_NAME;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.bootstrap;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.cluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createCluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createEndpoint;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.loadAssignment;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.updateCluster;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.xds.ClusterRoot;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

final class CdsStreamingMultipleClientsTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @Test
    void updateClusterRootWithCorrespondingResource() throws Exception {
        final String fooGroupName = "groups/foo";
        final String barGroupName = "groups/bar";
        final String fooClusterId = "foo-cluster";
        final String barClusterId = "bar-cluster";
        final String fooClusterName = fooGroupName + "/clusters/" + fooClusterId;
        final String barClusterName = barGroupName + "/clusters/" + barClusterId;
        final WebClient webClient = dogma.httpClient();
        final Bootstrap bootstrap = bootstrap(webClient.uri(), CONFIG_SOURCE_CLUSTER_NAME);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ClusterRoot fooClusterRoot = xdsBootstrap.clusterRoot(fooClusterName);
            final AtomicReference<ClusterSnapshot> fooSnapshotCaptor = new AtomicReference<>();
            fooClusterRoot.addSnapshotWatcher(fooSnapshotCaptor::set);

            final ClusterRoot barClusterRoot = xdsBootstrap.clusterRoot(barClusterName);
            final AtomicReference<ClusterSnapshot> barSnapshotCaptor = new AtomicReference<>();
            barClusterRoot.addSnapshotWatcher(barSnapshotCaptor::set);

            // not updated until commit
            await().pollDelay(200, TimeUnit.MILLISECONDS).until(() -> {
                return fooSnapshotCaptor.get() == null && barSnapshotCaptor.get() == null;
            });

            createGroup("foo", webClient);

            final ClusterLoadAssignment fooEndpoint = loadAssignment(fooClusterName, "127.0.0.1", 8080);
            createEndpoint(fooGroupName, fooClusterId, fooEndpoint, webClient);
            Cluster fooCluster = cluster(fooClusterName, 1);
            createCluster(fooGroupName, fooClusterId, fooCluster, webClient);

            await().until(() -> fooSnapshotCaptor.get() != null);
            ClusterSnapshot fooClusterSnapshot = fooSnapshotCaptor.getAndSet(null);
            assertThat(fooClusterSnapshot.xdsResource().resource()).isEqualTo(fooCluster);
            assertThat(fooClusterSnapshot.endpointSnapshot().xdsResource().resource()).isEqualTo(fooEndpoint);

            // bar is not updated.
            await().pollDelay(200, TimeUnit.MILLISECONDS).until(() -> barSnapshotCaptor.get() == null);
            createGroup("bar", webClient);
            final ClusterLoadAssignment barEndpoint = loadAssignment(barClusterName, "127.0.0.1", 8081);
            createEndpoint(barGroupName, barClusterId, barEndpoint, webClient);
            final Cluster barCluster = cluster(barClusterName, 1);
            createCluster(barGroupName, barClusterId, barCluster, webClient);
            await().until(() -> barSnapshotCaptor.get() != null);
            final ClusterSnapshot barClusterSnapshot = barSnapshotCaptor.getAndSet(null);
            assertThat(barClusterSnapshot.xdsResource().resource()).isEqualTo(barCluster);
            assertThat(barClusterSnapshot.endpointSnapshot().xdsResource().resource()).isEqualTo(barEndpoint);

            // foo is not updated.
            await().pollDelay(200, TimeUnit.MILLISECONDS).until(() -> fooSnapshotCaptor.get() == null);

            // Change the configuration.
            fooCluster = cluster(fooClusterName, 2);
            updateCluster(fooGroupName, fooClusterId, fooCluster, webClient);
            await().until(() -> fooSnapshotCaptor.get() != null);
            fooClusterSnapshot = fooSnapshotCaptor.getAndSet(null);
            assertThat(fooClusterSnapshot.xdsResource().resource()).isEqualTo(fooCluster);
            assertThat(fooClusterSnapshot.endpointSnapshot().xdsResource().resource()).isEqualTo(fooEndpoint);

            // bar is not updated.
            await().pollDelay(200, TimeUnit.MILLISECONDS).until(() -> barSnapshotCaptor.get() == null);
        }
    }
}
