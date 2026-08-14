/*
 * Copyright 2026 LY Corporation
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
package com.linecorp.centraldogma.xds.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import io.envoyproxy.controlplane.cache.SnapshotResources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;

class CentralDogmaSnapshotTest {

    @SuppressWarnings("unchecked")
    @Test
    void extensionConfigsAreServedAsEmpty() {
        final SnapshotResources<?> empty = SnapshotResources.create(ImmutableList.of(), "empty_resources");
        final CentralDogmaSnapshot snapshot = new CentralDogmaSnapshot(
                (SnapshotResources<Cluster>) empty,
                (SnapshotResources<ClusterLoadAssignment>) empty,
                (SnapshotResources<Listener>) empty,
                (SnapshotResources<RouteConfiguration>) empty,
                (SnapshotResources<Secret>) empty);

        // Central Dogma does not serve ECDS, so a request for it must resolve to an empty response
        // through the dispatch path the control plane uses, rather than failing.
        assertThat(snapshot.extensionConfigs()).isNotNull();
        assertThat(snapshot.extensionConfigs().resources()).isEmpty();
        assertThat(snapshot.resources(ResourceType.EXTENSION_CONFIG)).isEmpty();
        assertThat(snapshot.versionedResources(ResourceType.EXTENSION_CONFIG)).isEmpty();
        assertThat(snapshot.version(ResourceType.EXTENSION_CONFIG)).isNotNull();
    }
}
