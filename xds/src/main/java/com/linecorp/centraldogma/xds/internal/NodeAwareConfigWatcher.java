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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.xds.XdsResourceTransformer;

import io.envoyproxy.controlplane.cache.ConfigWatcher;
import io.envoyproxy.controlplane.cache.DeltaResponse;
import io.envoyproxy.controlplane.cache.DeltaWatch;
import io.envoyproxy.controlplane.cache.DeltaXdsRequest;
import io.envoyproxy.controlplane.cache.Response;
import io.envoyproxy.controlplane.cache.VersionedResource;
import io.envoyproxy.controlplane.cache.Watch;
import io.envoyproxy.controlplane.cache.XdsRequest;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.netty.util.AttributeKey;

final class NodeAwareConfigWatcher implements ConfigWatcher {

    private static final Logger logger = LoggerFactory.getLogger(NodeAwareConfigWatcher.class);

    private static final AttributeKey<Node> NODE_KEY = AttributeKey.valueOf(
            NodeAwareConfigWatcher.class, "xds.node");

    private final ConfigWatcher delegate;
    private final List<XdsResourceTransformer> transformers;

    NodeAwareConfigWatcher(ConfigWatcher delegate, List<XdsResourceTransformer> transformers) {
        this.delegate = delegate;
        this.transformers = ImmutableList.copyOf(transformers);
    }

    @Override
    public Watch createWatch(boolean ads, XdsRequest request, Set<String> knownResourceNames,
                             Consumer<Response> responseConsumer,
                             boolean hasClusterChanged, boolean allowDefaultEmptyEdsUpdate) {
        if (transformers.isEmpty()) {
            return delegate.createWatch(ads, request, knownResourceNames,
                                        responseConsumer, hasClusterChanged, allowDefaultEmptyEdsUpdate);
        }

        final ServiceRequestContext ctx = ServiceRequestContext.current();
        final Node node = resolveNode(request.v3Request().getNode(), ctx);
        final Consumer<Response> transforming = response -> {
            try (SafeCloseable ignored = ctx.push()) {
                deliverTransformed(response, node, request, responseConsumer);
            }
        };
        return delegate.createWatch(ads, request, knownResourceNames,
                                    transforming, hasClusterChanged, allowDefaultEmptyEdsUpdate);
    }

    private void deliverTransformed(Response response, Node node, XdsRequest request,
                                    Consumer<Response> responseConsumer) {
        try {
            final List<Message> transformed = transformAll(node, response.resources(), request.getTypeUrl());
            responseConsumer.accept(Response.create(request, transformed, response.version()));
        } catch (Exception e) {
            logger.warn("Failed to transform xDS resources for node '{}'; delivering untransformed response.",
                        node.getId(), e);
            responseConsumer.accept(response);
        }
    }

    @Override
    public DeltaWatch createDeltaWatch(DeltaXdsRequest request, String requiredVersion,
                                       Map<String, String> pendingResources,
                                       Set<String> knownResourceNames, boolean isWildcard,
                                       Consumer<DeltaResponse> responseConsumer,
                                       boolean allowDefaultEmptyEdsUpdate) {
        if (transformers.isEmpty()) {
            return delegate.createDeltaWatch(request, requiredVersion, pendingResources,
                                             knownResourceNames, isWildcard,
                                             responseConsumer, allowDefaultEmptyEdsUpdate);
        }

        final ServiceRequestContext ctx = ServiceRequestContext.current();
        final Node node = resolveNode(request.v3Request().getNode(), ctx);
        final Consumer<DeltaResponse> transforming = response -> {
            try (SafeCloseable ignored = ctx.push()) {
                deliverDeltaTransformed(response, node, request, responseConsumer);
            }
        };
        return delegate.createDeltaWatch(request, requiredVersion, pendingResources,
                                         knownResourceNames, isWildcard,
                                         transforming, allowDefaultEmptyEdsUpdate);
    }

    private void deliverDeltaTransformed(DeltaResponse response, Node node, DeltaXdsRequest request,
                                         Consumer<DeltaResponse> responseConsumer) {
        try {
            final String typeUrl = request.getTypeUrl();
            final ImmutableMap.Builder<String, VersionedResource<?>> builder = ImmutableMap.builder();
            for (Map.Entry<String, VersionedResource<?>> entry : response.resources().entrySet()) {
                final VersionedResource<?> vr = entry.getValue();
                final Message transformed = applyTransformers(node, vr.resource(), typeUrl);
                builder.put(entry.getKey(), VersionedResource.create(transformed, vr.version()));
            }
            responseConsumer.accept(DeltaResponse.create(request, builder.build(),
                                                         response.removedResources(), response.version()));
        } catch (Exception e) {
            logger.warn("Failed to transform xDS resources for node '{}'; delivering untransformed response.",
                        node.getId(), e);
            responseConsumer.accept(response);
        }
    }

    private List<Message> transformAll(Node node, Iterable<? extends Message> resources, String typeUrl) {
        final ImmutableList.Builder<Message> result = ImmutableList.builder();
        for (Message resource : resources) {
            result.add(applyTransformers(node, resource, typeUrl));
        }
        return result.build();
    }

    private Message applyTransformers(Node node, Message resource, String typeUrl) {
        Message result = resource;
        for (XdsResourceTransformer transformer : transformers) {
            result = transformer.transform(node, result, typeUrl);
        }
        return result;
    }

    /**
     * Resolves the {@link Node} for the current stream. If a non-empty node is sent, it is stored in the
     * {@link ServiceRequestContext} and used for subsequent requests. If an empty node is sent, the latest
     * non-empty node is used.
     */
    private static Node resolveNode(Node requestNode, ServiceRequestContext ctx) {
        if (requestNode.getSerializedSize() > 0) {
            ctx.setAttr(NODE_KEY, requestNode);
            return requestNode;
        }
        final Node stored = ctx.attr(NODE_KEY);
        return stored != null ? stored : Node.getDefaultInstance();
    }
}
