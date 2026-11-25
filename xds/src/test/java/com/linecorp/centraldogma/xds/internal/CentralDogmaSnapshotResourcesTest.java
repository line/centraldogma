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

    @Test
    void wildcardVersionDependsOnResourceVersionsNotProtobufHashCode() {
        // Same resource names and same per-resource versions, but different underlying protobuf content (hence
        // different Message.hashCode()). The wildcard version must be a function of the per-resource versions
        // only, so these two must be equal. This guards against regressing to hashing
        // VersionedResource.hashCode(), which folds in Message.hashCode() (the descriptor identity hash is not
        // stable across replicas).
        final SnapshotResources<Cluster> a = clusterSnapshot(
                versioned("foo/cluster", "v-foo", "content-a"),
                versioned("bar/cluster", "v-bar", "content-a"));
        final SnapshotResources<Cluster> b = clusterSnapshot(
                versioned("foo/cluster", "v-foo", "content-b"),
                versioned("bar/cluster", "v-bar", "content-b"));
        assertThat(a.version(ImmutableList.of())).isEqualTo(b.version(ImmutableList.of()));

        // A genuine per-resource version change must still change the wildcard version.
        final SnapshotResources<Cluster> c = clusterSnapshot(
                versioned("foo/cluster", "v-foo", "content-a"),
                versioned("bar/cluster", "v-bar-CHANGED", "content-a"));
        assertThat(c.version(ImmutableList.of())).isNotEqualTo(a.version(ImmutableList.of()));
    }

    @Test
    void namedSubsetVersionDependsOnResourceVersionsNotProtobufHashCode() {
        // Same as above, but for an explicit subset of resource names (the multi-resource branch).
        final SnapshotResources<Cluster> a = clusterSnapshot(
                versioned("foo/cluster", "v-foo", "content-a"),
                versioned("bar/cluster", "v-bar", "content-a"),
                versioned("baz/cluster", "v-baz", "content-a"));
        final SnapshotResources<Cluster> b = clusterSnapshot(
                versioned("foo/cluster", "v-foo", "content-b"),
                versioned("bar/cluster", "v-bar", "content-b"),
                versioned("baz/cluster", "v-baz", "content-b"));
        final ImmutableList<String> subset = ImmutableList.of("foo/cluster", "bar/cluster");
        assertThat(a.version(subset)).isEqualTo(b.version(subset));
        // The subset version differs from the full (wildcard) version.
        assertThat(a.version(subset)).isNotEqualTo(a.version(ImmutableList.of()));
        // Order of the requested names does not matter.
        assertThat(a.version(subset)).isEqualTo(a.version(ImmutableList.of("bar/cluster", "foo/cluster")));
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

    @SafeVarargs
    private static SnapshotResources<Cluster> clusterSnapshot(VersionedResource<Cluster>... resources) {
        final ImmutableMap.Builder<String, Map<String, VersionedResource<Cluster>>> builder =
                ImmutableMap.builder();
        for (VersionedResource<Cluster> resource : resources) {
            final String name = resource.resource().getName();
            builder.put(name, ImmutableMap.of(name, resource));
        }
        return CentralDogmaSnapshotResources.create(builder.build(), ResourceType.CLUSTER);
    }

    // Builds a Cluster with the given name and an arbitrary content knob (altStatName), paired with an explicit
    // version. The explicit version lets a test hold the version fixed while varying the content, so it can
    // assert the aggregate version ignores the protobuf content (and its unstable hashCode) and depends only on
    // the per-resource versions.
    private static VersionedResource<Cluster> versioned(String name, String version, String content) {
        return VersionedResource.create(
                Cluster.newBuilder().setName(name).setAltStatName(content).build(), version);
    }
}
