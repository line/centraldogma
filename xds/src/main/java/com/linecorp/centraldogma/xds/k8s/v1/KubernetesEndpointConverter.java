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
package com.linecorp.centraldogma.xds.k8s.v1;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.kubernetes.endpoints.KubernetesResourceAccess;

import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;

/**
 * Converts Kubernetes {@link Endpoint}s into Envoy {@link LbEndpoint}s, applying the
 * {@link ServiceEndpointWatcher#getDistinctEndpoint() distinct_endpoint} and
 * {@link ServiceEndpointWatcher#getMetadataMappingList() metadata_mapping} options.
 */
final class KubernetesEndpointConverter {

    private static final String DEFAULT_METADATA_NAMESPACE = "envoy.lb";

    /**
     * Appends an {@link LbEndpoint} to the specified {@code builder} for each {@link Endpoint}, collapsing
     * endpoints that share the same host and port when {@code distinct_endpoint} is enabled and copying the
     * Pod/Node label/annotation values described by {@code metadata_mapping} into the endpoint metadata.
     */
    static void addLbEndpoints(LocalityLbEndpoints.Builder builder, Iterable<Endpoint> endpoints,
                               ServiceEndpointWatcher watcher) {
        final boolean distinct = watcher.getDistinctEndpoint();
        final List<MetadataMapping> mappings = watcher.getMetadataMappingList();
        final Set<String> seen = distinct ? new HashSet<>() : null;
        for (Endpoint endpoint : endpoints) {
            if (!endpoint.hasPort()) {
                continue;
            }
            if (seen != null && !seen.add(endpoint.host() + ':' + endpoint.port())) {
                continue;
            }
            final SocketAddress socketAddress = SocketAddress.newBuilder()
                                                             .setAddress(endpoint.host())
                                                             .setPortValue(endpoint.port())
                                                             .build();
            final LbEndpoint.Builder lbEndpointBuilder =
                    LbEndpoint.newBuilder()
                              .setEndpoint(io.envoyproxy.envoy.config.endpoint.v3.Endpoint.newBuilder()
                                                    .setAddress(Address.newBuilder()
                                                                       .setSocketAddress(socketAddress)
                                                                       .build())
                                                    .build());
            final Metadata metadata = buildMetadata(endpoint, mappings);
            if (metadata != null) {
                lbEndpointBuilder.setMetadata(metadata);
            }
            builder.addLbEndpoints(lbEndpointBuilder.build());
        }
    }

    @Nullable
    private static Metadata buildMetadata(Endpoint endpoint, List<MetadataMapping> mappings) {
        if (mappings.isEmpty()) {
            return null;
        }
        final Map<String, Struct.Builder> structsByNamespace = new LinkedHashMap<>();
        for (MetadataMapping mapping : mappings) {
            final ObjectMeta objectMeta = objectMeta(endpoint, mapping.getResourceType());
            if (objectMeta == null) {
                continue;
            }
            final Map<String, String> source =
                    mapping.getEntryType() == MetadataMapping.EntryType.ANNOTATION ? objectMeta.getAnnotations()
                                                                                   : objectMeta.getLabels();
            if (source == null || source.isEmpty()) {
                continue;
            }
            final String namespace = mapping.getMetadataNamespace().isEmpty() ? DEFAULT_METADATA_NAMESPACE
                                                                              : mapping.getMetadataNamespace();
            switch (mapping.getSourceCase()) {
                case SOURCE_KEY:
                    // Exact match: the destination key is metadata_key or, if empty, the source key.
                    final String value = source.get(mapping.getSourceKey());
                    if (value != null) {
                        final String key = mapping.getMetadataKey().isEmpty() ? mapping.getSourceKey()
                                                                              : mapping.getMetadataKey();
                        putField(structsByNamespace, namespace, key, value);
                    }
                    break;
                case SOURCE_KEY_PREFIX:
                    // Prefix match: copy every matching entry, preserving the original key.
                    final String prefix = mapping.getSourceKeyPrefix();
                    for (Map.Entry<String, String> entry : source.entrySet()) {
                        if (entry.getKey().startsWith(prefix)) {
                            putField(structsByNamespace, namespace, entry.getKey(), entry.getValue());
                        }
                    }
                    break;
                default:
                    // SOURCE_NOT_SET is rejected by validation.
                    break;
            }
        }
        if (structsByNamespace.isEmpty()) {
            return null;
        }
        final Metadata.Builder metadataBuilder = Metadata.newBuilder();
        structsByNamespace.forEach((namespace, struct) ->
                                           metadataBuilder.putFilterMetadata(namespace, struct.build()));
        return metadataBuilder.build();
    }

    private static void putField(Map<String, Struct.Builder> structsByNamespace, String namespace,
                                 String key, String value) {
        structsByNamespace.computeIfAbsent(namespace, unused -> Struct.newBuilder())
                          .putFields(key, Value.newBuilder().setStringValue(value).build());
    }

    @Nullable
    private static ObjectMeta objectMeta(Endpoint endpoint, MetadataMapping.ResourceType resourceType) {
        switch (resourceType) {
            case POD:
                final Pod pod = KubernetesResourceAccess.pod(endpoint);
                return pod != null ? pod.getMetadata() : null;
            case NODE:
                final Node node = KubernetesResourceAccess.node(endpoint);
                return node != null ? node.getMetadata() : null;
            default:
                return null;
        }
    }

    private KubernetesEndpointConverter() {}
}
