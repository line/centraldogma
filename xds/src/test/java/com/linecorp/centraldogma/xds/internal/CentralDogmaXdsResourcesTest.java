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

import com.google.common.hash.Hashing;
import com.google.protobuf.CodedOutputStream;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;

class CentralDogmaXdsResourcesTest {

    @Test
    void stableVersionIsHashOfDeterministicSerialization() throws Exception {
        final Cluster cluster = Cluster.newBuilder().setName("foo").setAltStatName("bar").build();

        // The version must be the SHA-256 of the deterministically serialized bytes, not something derived from
        // Cluster.hashCode() (which folds in the descriptor identity hash and is not stable across replicas).
        final byte[] serialized = new byte[cluster.getSerializedSize()];
        final CodedOutputStream out = CodedOutputStream.newInstance(serialized);
        out.useDeterministicSerialization();
        cluster.writeTo(out);
        out.checkNoSpaceLeft();
        final String expected = Hashing.sha256().hashBytes(serialized).toString();

        assertThat(CentralDogmaXdsResources.stableVersion(cluster)).isEqualTo(expected);
        assertThat(CentralDogmaXdsResources.stableVersion(cluster)).hasSize(64); // SHA-256 hex length.
    }

    @Test
    void stableVersionIsContentBasedAndStable() {
        final Cluster a1 = Cluster.newBuilder().setName("foo").setAltStatName("x").build();
        final Cluster a2 = Cluster.newBuilder().setName("foo").setAltStatName("x").build();
        // Identical content -> identical version, regardless of the message instance.
        assertThat(CentralDogmaXdsResources.stableVersion(a1))
                .isEqualTo(CentralDogmaXdsResources.stableVersion(a2));

        // Different content -> different version.
        final Cluster b = Cluster.newBuilder().setName("foo").setAltStatName("y").build();
        assertThat(CentralDogmaXdsResources.stableVersion(a1))
                .isNotEqualTo(CentralDogmaXdsResources.stableVersion(b));
    }
}
