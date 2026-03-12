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

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.protobuf.Message;

import io.envoyproxy.controlplane.cache.ResourceVersionResolver;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import io.envoyproxy.controlplane.cache.SnapshotResources;
import io.envoyproxy.controlplane.cache.VersionedResource;

final class CentralDogmaSnapshotResources<T extends Message> extends SnapshotResources<T> {

    public static <T extends Message> SnapshotResources<T> create(
            Map<String, Map<String, VersionedResource<T>>> resources, ResourceType resourceType) {
        final ImmutableMap.Builder<String, VersionedResource<T>> versionedResourcesMap = ImmutableMap.builder();
        final ImmutableMap.Builder<String, T> resourcesMap = ImmutableMap.builder();
        for (Map<String, VersionedResource<T>> value : resources.values()) {
            value.values().forEach(versionedResource -> {
                final String resourceName = Resources.getResourceName(versionedResource.resource());
                versionedResourcesMap.put(resourceName, versionedResource);
                resourcesMap.put(resourceName, versionedResource.resource());
            });
        }
        return new CentralDogmaSnapshotResources<>(
                versionedResourcesMap.build(), resourcesMap.build(), resourceType);
    }

    private final Map<String, VersionedResource<T>> versionedResources;
    private final Map<String, T> resources;
    private final ResourceVersionResolver resourceVersionResolver;
    @Nullable
    private String allResourceVersion;

    private CentralDogmaSnapshotResources(
            Map<String, VersionedResource<T>> versionedResources, ImmutableMap<String, T> resources,
            ResourceType resourceType) {
        this.versionedResources = versionedResources;
        this.resources = resources;
        resourceVersionResolver = resourceNames -> {
            if (resourceNames.isEmpty()) {
                return allResourceOrThrow(resourceType);
            }
            if (resourceNames.size() == 1) {
                final String resourceName = resourceNames.get(0);
                if ("*".equals(resourceName)) {
                    return allResourceOrThrow(resourceType);
                }
                final VersionedResource<T> versionedResource = versionedResources.get(resourceNames.get(0));
                if (versionedResource == null) {
                    return "";
                }
                return versionedResource.version();
            }

            final List<VersionedResource<T>> collected = resourceNames.stream().sorted().distinct()
                                                                      .map(versionedResources::get)
                                                                      .filter(Objects::nonNull)
                                                                      .collect(toImmutableList());
            if (collected.isEmpty()) {
                // There is no resource with the given names.
                return "";
            }
            if (collected.size() == 1) {
                return collected.get(0).version();
            }
            if (collected.size() == versionedResources.size()) {
                return allResourceVersion();
            }

            return Hashing.sha256().hashInt(collected.hashCode()).toString();
        };
    }

    private String allResourceOrThrow(ResourceType resourceType) {
        if (resourceType == ResourceType.CLUSTER || resourceType == ResourceType.LISTENER) {
            return allResourceVersion();
        }
        throw new IllegalArgumentException("Requesting all resource isn't allowed for " + resourceType);
    }

    String allResourceVersion() {
        if (allResourceVersion != null) {
            return allResourceVersion;
        }
        return allResourceVersion = Hashing.sha256()
                                           .hashInt(versionedResources.values().hashCode())
                                           .toString();
    }

    @Override
    public Map<String, VersionedResource<T>> versionedResources() {
        return versionedResources;
    }

    @Override
    public Map<String, T> resources() {
        return resources;
    }

    @Override
    public ResourceVersionResolver resourceVersionResolver() {
        return resourceVersionResolver;
    }
}
