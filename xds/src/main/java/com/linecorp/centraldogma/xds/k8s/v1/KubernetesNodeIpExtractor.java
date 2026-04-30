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

import java.util.ServiceLoader;

import org.jspecify.annotations.Nullable;

import io.fabric8.kubernetes.api.model.Node;

/**
 * An SPI for extracting the IP address of a Kubernetes {@link Node} that backs a
 * {@link ServiceEndpointWatcher}.
 *
 * <p>An implementation is discovered via {@link ServiceLoader} and used by the xDS
 * Kubernetes integration to determine which IP address should be used for each {@link Node}
 * when resolving a Service's endpoints. If no implementation is registered, the default
 * <a href="https://kubernetes.io/docs/reference/node/node-status/#addresses">{@code InternalIP}</a>-based
 * extraction is used.
 *
 * <p>The watcher's
 * {@link ServiceEndpointWatcher#getAdditionalPropertiesMap() additional properties}
 * are made available so that the extractor can be configured per watcher
 * (for example, a label or annotation key to read the IP from).
 */
@FunctionalInterface
public interface KubernetesNodeIpExtractor {

    /**
     * Extracts a Node IP address from the given {@link Node}.
     * If {@code null} is returned, the {@link Node} will not be included in the endpoint list.
     */
    @Nullable
    String extract(ServiceEndpointWatcher watcherConfig, Node node);
}
