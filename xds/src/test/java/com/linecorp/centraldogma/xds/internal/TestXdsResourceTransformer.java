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

import com.google.protobuf.Message;
import com.google.protobuf.Value;

import com.linecorp.centraldogma.xds.XdsResourceTransformer;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Node;

/**
 * A test {@link XdsResourceTransformer} that sets {@link Cluster#getAltStatName()} to
 * {@code <cluster_name>-<node.metadata.alt_stat_suffix>} when the requesting node carries the
 * {@code alt_stat_suffix} metadata key. Requests without this metadata key are passed through unchanged,
 * so existing tests are not affected.
 */
public final class TestXdsResourceTransformer implements XdsResourceTransformer {

    @Override
    public Message transform(Node node, Message resource, String typeUrl) {
        if (!(resource instanceof Cluster)) {
            return resource;
        }
        final Value suffix = node.getMetadata().getFieldsMap().get("alt_stat_suffix");
        if (suffix == null) {
            return resource;
        }
        final Cluster cluster = (Cluster) resource;
        return cluster.toBuilder()
                      .setAltStatName(cluster.getName() + '-' + suffix.getStringValue())
                      .build();
    }
}
