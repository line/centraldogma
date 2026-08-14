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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import io.envoyproxy.controlplane.cache.SnapshotResources;
import io.envoyproxy.controlplane.cache.VersionedResource;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;

class CentralDogmaSnapshotResourcesTest {

    @Test
    void resourceVersion() {
        final ImmutableMap.Builder<String, Map<String, VersionedResource<Cluster>>> builder =
                ImmutableMap.builder();
        builder.put("foo", ImmutableMap.<String, VersionedResource<Cluster>>builder()
                                       .put("foo/cluster", VersionedResource.create(
                                               Cluster.newBuilder().setName("foo/cluster").build()))
                                       .build());
        builder.put("bar", ImmutableMap.<String, VersionedResource<Cluster>>builder()
                                       .put("bar/cluster", VersionedResource.create(
                                               Cluster.newBuilder().setName("bar/cluster").build()))
                                       .build());
        builder.put("baz", ImmutableMap.<String, VersionedResource<Cluster>>builder()
                                       .put("baz/cluster", VersionedResource.create(
                                               Cluster.newBuilder().setName("baz/cluster").build()))
                                       .build());
        final SnapshotResources<Cluster> snapshotResources = CentralDogmaSnapshotResources.create(
                builder.build(), ResourceType.CLUSTER);

        // Each resource has different versions.
        final String fooVersion = snapshotResources.version(ImmutableList.of("foo/cluster"));
        assertThat(fooVersion.length()).isEqualTo(64); // sha 256 hash length is 64. 256/4
        final String barVersion = snapshotResources.version(ImmutableList.of("bar/cluster"));
        assertThat(barVersion.length()).isEqualTo(64);
        assertThat(fooVersion).isNotEqualTo(barVersion);

        assertThat(snapshotResources.version(ImmutableList.of())).isEqualTo(
                snapshotResources.version(ImmutableList.of("foo/cluster", "bar/cluster", "baz/cluster")));

        // The version for more than one resource is a hash of the versions of the resources.
        final String fooBarVersion = snapshotResources.version(ImmutableList.of("foo/cluster", "bar/cluster"));
        assertThat(fooBarVersion.length()).isEqualTo(64);
        assertThat(fooBarVersion).isNotEqualTo(fooVersion);
        assertThat(fooBarVersion).isNotEqualTo(barVersion);

        // Order of resource names does not matter.
        assertThat(fooBarVersion).isEqualTo(snapshotResources.version(
                ImmutableList.of("bar/cluster", "foo/cluster")));

        // Resources that do not exist are ignored.
        assertThat(snapshotResources.version(ImmutableList.of("foo/cluster", "bar/cluster", "qux/cluster")))
                .isEqualTo(fooBarVersion);
    }

    @Test
    void wildcardVersionIsStableRegardlessOfInsertionOrder() {
        // The same three clusters.
        final SnapshotResources<Cluster> ascending = clusterSnapshot("foo", "bar", "baz");
        final SnapshotResources<Cluster> descending = clusterSnapshot("baz", "bar", "foo");

        // The wildcard version (empty resource names) must be identical for identical content.
        assertThat(ascending.version(ImmutableList.of()))
                .isEqualTo(descending.version(ImmutableList.of()));
        // "*" and the explicit full set resolve to the same wildcard version too.
        assertThat(ascending.version(ImmutableList.of("*")))
                .isEqualTo(descending.version(ImmutableList.of()));
        assertThat(ascending.version(ImmutableList.of("foo/cluster", "bar/cluster", "baz/cluster")))
                .isEqualTo(descending.version(ImmutableList.of()));

        // A genuine content change must still change the wildcard version.
        final SnapshotResources<Cluster> changed = clusterSnapshot("foo", "bar", "qux");
        assertThat(changed.version(ImmutableList.of()))
                .isNotEqualTo(ascending.version(ImmutableList.of()));
    }

    private static SnapshotResources<Cluster> clusterSnapshot(String... groups) {
        final ImmutableMap.Builder<String, Map<String, VersionedResource<Cluster>>> builder =
                ImmutableMap.builder();
        for (String group : groups) {
            final String clusterName = group + "/cluster";
            builder.put(group, ImmutableMap.of(
                    clusterName, VersionedResource.create(Cluster.newBuilder().setName(clusterName).build())));
        }
        return CentralDogmaSnapshotResources.create(builder.build(), ResourceType.CLUSTER);
    }
}
