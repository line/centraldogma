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
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createClusterAndCommit;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createEndpointAndCommit;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createListenerAndCommit;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createRouteConfigurationAndCommit;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createXdsProject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.centraldogma.server.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.management.ServerStatusManager;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

final class LdsStreamingMultipleClientsTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @TempDir
    static File tempDir;

    @SuppressWarnings("NotNullFieldNotInitialized")
    static MetadataService metadataService;

    // This method will be remove once XdsProjectService is added in a follow-up PR.
    @BeforeAll
    static void setup() {
        final StandaloneCommandExecutor executor = new StandaloneCommandExecutor(
                dogma.projectManager(), ForkJoinPool.commonPool(),
                new ServerStatusManager(tempDir), null, null, null);
        executor.start().join();
        metadataService = new MetadataService(dogma.projectManager(), executor);
    }

    @Test
    void updateListenerRootWithCorrespondingResource() throws Exception {
        final String fooXdsProjectName = "foo";
        final String barXdsProjectName = "bar";
        final String fooListenerName = "foo/listener";
        final String fooRouteName = "foo/route";
        final String fooClusterName = "foo/cluster";
        final String barListenerName = "bar/listener";
        final ProjectManager projectManager = dogma.projectManager();
        createXdsProject(projectManager, metadataService, fooXdsProjectName);
        final ClusterLoadAssignment fooEndpoint =
                createEndpointAndCommit(fooXdsProjectName, fooClusterName, projectManager);
        final Cluster fooCluster = createClusterAndCommit(fooXdsProjectName, fooClusterName, 1, projectManager);
        final RouteConfiguration fooRoute = createRouteConfigurationAndCommit(fooXdsProjectName,
                                                                              fooRouteName,
                                                                              fooClusterName,
                                                                              projectManager);

        final Bootstrap bootstrap = bootstrap(dogma.httpClient().uri(), CONFIG_SOURCE_CLUSTER_NAME);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot fooListenerRoot = xdsBootstrap.listenerRoot(fooListenerName);
            final AtomicReference<ListenerSnapshot> fooSnapshotCaptor = new AtomicReference<>();
            fooListenerRoot.addSnapshotWatcher(fooSnapshotCaptor::set);

            final ListenerRoot barListenerRoot = xdsBootstrap.listenerRoot(barListenerName);
            final AtomicReference<ListenerSnapshot> barSnapshotCaptor = new AtomicReference<>();
            barListenerRoot.addSnapshotWatcher(barSnapshotCaptor::set);

            // not updated until commit
            await().pollDelay(200, TimeUnit.MILLISECONDS).until(() -> {
                return fooSnapshotCaptor.get() == null && barSnapshotCaptor.get() == null;
            });

            Listener fooListener =
                    createListenerAndCommit(fooXdsProjectName, fooListenerName, fooRouteName, "a",
                                            projectManager);

            await().until(() -> fooSnapshotCaptor.get() != null);
            ListenerSnapshot fooListenerSnapshot = fooSnapshotCaptor.getAndSet(null);
            assertThat(fooListenerSnapshot.xdsResource().resource()).isEqualTo(fooListener);
            final RouteSnapshot routeSnapshot = fooListenerSnapshot.routeSnapshot();
            assertThat(routeSnapshot.xdsResource().resource()).isEqualTo(fooRoute);
            final List<ClusterSnapshot> clusterSnapshots = routeSnapshot.clusterSnapshots();
            assertThat(clusterSnapshots.size()).isOne();
            final ClusterSnapshot clusterSnapshot = clusterSnapshots.get(0);
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(fooCluster);
            assertThat(clusterSnapshot.endpointSnapshot().xdsResource().resource()).isEqualTo(fooEndpoint);

            // bar is not updated.
            await().pollDelay(200, TimeUnit.MILLISECONDS).until(() -> barSnapshotCaptor.get() == null);
            createXdsProject(projectManager, metadataService, barXdsProjectName);
            final Listener barListener =
                    createListenerAndCommit(barXdsProjectName, barListenerName, fooRouteName, "a",
                                            projectManager);
            await().until(() -> barSnapshotCaptor.get() != null);
            final ListenerSnapshot barListenerSnapshot = barSnapshotCaptor.getAndSet(null);
            assertThat(barListenerSnapshot.xdsResource().resource()).isEqualTo(barListener);
            assertThat(barListenerSnapshot.routeSnapshot().xdsResource().resource()).isEqualTo(fooRoute);

            // Change the configuration.
            fooListener = createListenerAndCommit(fooXdsProjectName, fooListenerName, fooRouteName, "b",
                                                  projectManager);
            await().until(() -> fooSnapshotCaptor.get() != null);
            fooListenerSnapshot = fooSnapshotCaptor.getAndSet(null);
            assertThat(fooListenerSnapshot.xdsResource().resource()).isEqualTo(fooListener);

            // bar is not updated.
            await().pollDelay(200, TimeUnit.MILLISECONDS).until(() -> barSnapshotCaptor.get() == null);
        }
    }
}
