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

import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Revision;

import io.envoyproxy.controlplane.cache.SnapshotResources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;

class CentralDogmaSnapshotResourcesTest {

    @Test
    void resourceVersion() {
        final Cluster fooCluster = Cluster.newBuilder().setName("foo").build();
        final Cluster barCluster = Cluster.newBuilder().setName("bar").build();
        final Cluster bazCluster = Cluster.newBuilder().setName("baz").build();
        final SnapshotResources<Cluster> snapshotResources = CentralDogmaSnapshotResources.create(
                ImmutableList.of(fooCluster, barCluster, bazCluster), new Revision(3));

        // Each resource has different versions.
        final String fooVersion = snapshotResources.version(ImmutableList.of("foo"));
        final String barVersion = snapshotResources.version(ImmutableList.of("bar"));
        assertThat(fooVersion).isNotEqualTo(barVersion);

        // The version of all resources is the revision of the snapshot.
        assertThat(snapshotResources.version(ImmutableList.of())).isEqualTo("3");
        assertThat(snapshotResources.version(ImmutableList.of("foo", "bar", "baz"))).isEqualTo("3");

        // The version for more than one resource is a hash of the versions of the resources.
        final String fooBarVersion = snapshotResources.version(ImmutableList.of("foo", "bar"));
        assertThat(fooBarVersion).isNotEqualTo(fooVersion);
        assertThat(fooBarVersion).isNotEqualTo(barVersion);

        // Order of resource names does not matter.
        assertThat(fooBarVersion).isEqualTo(snapshotResources.version(ImmutableList.of("bar", "foo")));

        // Resources that do not exist are ignored.
        assertThat(snapshotResources.version(ImmutableList.of("foo", "bar", "qux"))) // qux does not exist
                .isEqualTo(fooBarVersion);
    }
}
