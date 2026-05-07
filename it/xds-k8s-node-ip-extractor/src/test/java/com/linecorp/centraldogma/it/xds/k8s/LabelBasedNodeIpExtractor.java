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
package com.linecorp.centraldogma.it.xds.k8s;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jspecify.annotations.Nullable;

import com.linecorp.centraldogma.xds.k8s.v1.KubernetesNodeIpExtractor;
import com.linecorp.centraldogma.xds.k8s.v1.ServiceEndpointWatcher;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;

public final class LabelBasedNodeIpExtractor implements KubernetesNodeIpExtractor {

    static final String NODE_IP_LABEL_PROPERTY = "nodeIpLabel";

    private static final Queue<ServiceEndpointWatcher> invocations = new ConcurrentLinkedQueue<>();

    public static Queue<ServiceEndpointWatcher> invocations() {
        return invocations;
    }

    @Nullable
    @Override
    public String extract(ServiceEndpointWatcher watcherConfig, Node node) {
        invocations.add(watcherConfig);
        final String labelKey = watcherConfig.getAdditionalPropertiesOrDefault(NODE_IP_LABEL_PROPERTY, null);
        if (labelKey == null) {
            return internalIp(node);
        }
        final Map<String, String> labels = node.getMetadata().getLabels();
        if (labels == null) {
            return null;
        }
        return labels.get(labelKey);
    }

    private static String internalIp(Node node) {
        if (node.getStatus() == null || node.getStatus().getAddresses() == null) {
            return null;
        }
        for (NodeAddress address : node.getStatus().getAddresses()) {
            if ("InternalIP".equals(address.getType()) && address.getAddress() != null &&
                !address.getAddress().isEmpty()) {
                return address.getAddress();
            }
        }
        return null;
    }
}
