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
package com.linecorp.centraldogma.xds;

import java.util.ServiceLoader;

import com.google.protobuf.Message;

import io.envoyproxy.envoy.config.core.v3.Node;

/**
 * An SPI for transforming xDS resources before they are sent to a requesting node.
 *
 * <p>An implementation is discovered via {@link ServiceLoader} and used by the xDS control plane
 * to transform resources (e.g., {@code Cluster}, {@code Listener}, {@code RouteConfiguration})
 * based on the requesting {@link Node}'s properties. This allows per-node customization of xDS
 * responses without duplicating resources in the repository.
 *
 * <p>For example, an implementation could inspect {@link Node#getMetadata()} and substitute
 * TLS certificate file paths based on a node-specific metadata field:
 * <pre>{@code
 * public class CertPathTransformer implements XdsResourceTransformer {
 *     @Override
 *     public Message transform(Node node, Message resource, String typeUrl) {
 *         if (!(resource instanceof Listener)) {
 *             return resource;
 *         }
 *         String certDir = node.getMetadata()
 *                 .getFieldsOrDefault("cert_dir",
 *                     Value.newBuilder().setStringValue("/default/certs").build())
 *                 .getStringValue();
 *         // ... rebuild the Listener with modified cert paths
 *     }
 * }
 * }</pre>
 *
 * <p>Multiple implementations may be registered; they are applied in {@link ServiceLoader} iteration order.
 * Each transformer receives the output of the previous one.
 */
@FunctionalInterface
public interface XdsResourceTransformer {

    /**
     * Transforms an xDS resource before it is sent to the requesting node.
     *
     * @param node     the requesting Envoy node (from the {@code DiscoveryRequest})
     * @param resource the original resource
     *                 (e.g., {@code Cluster}, {@code Listener}, {@code RouteConfiguration},
     *                 {@code ClusterLoadAssignment})
     * @param typeUrl  the xDS type URL
     *                 (e.g., {@code "type.googleapis.com/envoy.config.cluster.v3.Cluster"})
     * @return the transformed resource, or the original {@code resource} if no transformation is needed
     */
    Message transform(Node node, Message resource, String typeUrl);
}
