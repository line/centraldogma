/*
 * Copyright 2024 LINE Corporation
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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Message;

import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import io.envoyproxy.controlplane.cache.SnapshotResources;
import io.envoyproxy.controlplane.cache.VersionedResource;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;

final class CentralDogmaXdsResources {

    private static final Pattern ENDPOINTS_PATTERN = Pattern.compile("/endpoints/");

    private final Map<String, Map<String, VersionedResource<Cluster>>> clusterResources = new HashMap<>();
    private final Map<String, Map<String, VersionedResource<ClusterLoadAssignment>>> endpointResources =
            new HashMap<>();
    private final Map<String, Map<String, VersionedResource<Listener>>> listenerResources = new HashMap<>();
    private final Map<String, Map<String, VersionedResource<RouteConfiguration>>> routeResources =
            new HashMap<>();
    private boolean clusterUpdated;
    private boolean endpointUpdated;
    private boolean listenerUpdated;
    private boolean routeUpdated;

    // The snapshot and its per-group indexes, published together as one immutable object. It is replaced only
    // on the control plane executor inside snapshot() and read from gRPC threads via snapshot(Set). Bundling
    // them into a single volatile reference ensures a reader never observes a per-group index from one revision
    // together with a snapshot from another (mixed-revision) revision.
    private volatile XdsState state;

    CentralDogmaXdsResources() {
        final SnapshotResources<?> emptyResources = SnapshotResources.create(ImmutableList.of(),
                                                                             "empty_resources");
        final CentralDogmaSnapshot emptySnapshot =
                new CentralDogmaSnapshot((SnapshotResources<Cluster>) emptyResources,
                                         (SnapshotResources<ClusterLoadAssignment>) emptyResources,
                                         (SnapshotResources<Listener>) emptyResources,
                                         (SnapshotResources<RouteConfiguration>) emptyResources,
                                         (SnapshotResources<Secret>) emptyResources);
        state = new XdsState(emptySnapshot, ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of());
    }

    void setCluster(String groupName, Cluster cluster) {
        final Map<String, VersionedResource<Cluster>> groupClusters =
                clusterResources.computeIfAbsent(groupName, k -> new HashMap<>());
        groupClusters.put(cluster.getName(), VersionedResource.create(cluster));
        clusterUpdated = true;
    }

    void setEndpoint(String groupName, ClusterLoadAssignment endpoint) {
        final Map<String, VersionedResource<ClusterLoadAssignment>> groupEndpoints =
                endpointResources.computeIfAbsent(groupName, k -> new HashMap<>());
        groupEndpoints.put(endpoint.getClusterName(), VersionedResource.create(endpoint));
        endpointUpdated = true;
    }

    void setListener(String groupName, Listener listener) {
        final Map<String, VersionedResource<Listener>> groupListeners =
                listenerResources.computeIfAbsent(groupName, k -> new HashMap<>());
        groupListeners.put(listener.getName(), VersionedResource.create(listener));
        listenerUpdated = true;
    }

    void setRoute(String groupName, RouteConfiguration route) {
        final Map<String, VersionedResource<RouteConfiguration>> groupRoutes =
                routeResources.computeIfAbsent(groupName, k -> new HashMap<>());
        groupRoutes.put(route.getName(), VersionedResource.create(route));
        routeUpdated = true;
    }

    void removeCluster(String groupName, String path) {
        final Map<String, VersionedResource<Cluster>> groupClusters = clusterResources.get(groupName);
        if (groupClusters == null) {
            return;
        }
        clusterUpdated |= groupClusters.remove(getResourceName(groupName, path)) != null;
    }

    private static String getResourceName(String groupName, String path) {
        return "groups/" + groupName + path.substring(0, path.length() - 5); // Remove .json
    }

    void removeEndpoint(String groupName, String path) {
        final Map<String, VersionedResource<ClusterLoadAssignment>> groupEndpoints =
                endpointResources.get(groupName);
        if (groupEndpoints == null) {
            return;
        }
        // e.g. /endpoints/foo-cluster.json file with group foo -> groups/foo/clusters/foo-cluster
        // e.g. /k8s/endpoints/foo-cluster.json file with group foo -> groups/foo/k8s/clusters/foo-cluster
        final String clusterName =
                "groups/" + groupName +
                ENDPOINTS_PATTERN.matcher(path.substring(0, path.length() - 5) /* remove .json */)
                                 .replaceFirst("/clusters/");
        endpointUpdated |= groupEndpoints.remove(clusterName) != null;
    }

    void removeListener(String groupName, String path) {
        final Map<String, VersionedResource<Listener>> groupListeners = listenerResources.get(groupName);
        if (groupListeners == null) {
            return;
        }
        listenerUpdated |= groupListeners.remove(getResourceName(groupName, path)) != null;
    }

    void removeRoute(String groupName, String path) {
        final Map<String, VersionedResource<RouteConfiguration>> groupRoutes =
                routeResources.get(groupName);
        if (groupRoutes == null) {
            return;
        }
        routeUpdated |= groupRoutes.remove(getResourceName(groupName, path)) != null;
    }

    boolean isEndpointUpdated() {
        return endpointUpdated;
    }

    CentralDogmaSnapshot snapshot() {
        final XdsState prev = state;
        final CentralDogmaSnapshot prevSnapshot = prev.snapshot;

        final SnapshotResources<Cluster> clusters;
        final Map<String, Map<String, VersionedResource<Cluster>>> clustersByGroup;
        if (clusterUpdated) {
            clusters = CentralDogmaSnapshotResources.create(clusterResources, ResourceType.CLUSTER);
            clustersByGroup = immutableByGroup(clusterResources);
            clusterUpdated = false;
        } else {
            clusters = prevSnapshot.clusters();
            clustersByGroup = prev.clustersByGroup;
        }

        final SnapshotResources<ClusterLoadAssignment> endpoints;
        if (endpointUpdated) {
            endpoints = CentralDogmaSnapshotResources.create(endpointResources, ResourceType.ENDPOINT);
            endpointUpdated = false;
        } else {
            endpoints = prevSnapshot.endpoints();
        }

        final SnapshotResources<Listener> listeners;
        final Map<String, Map<String, VersionedResource<Listener>>> listenersByGroup;
        if (listenerUpdated) {
            listeners = CentralDogmaSnapshotResources.create(listenerResources, ResourceType.LISTENER);
            listenersByGroup = immutableByGroup(listenerResources);
            listenerUpdated = false;
        } else {
            listeners = prevSnapshot.listeners();
            listenersByGroup = prev.listenersByGroup;
        }

        final SnapshotResources<RouteConfiguration> routes;
        final Map<String, Map<String, VersionedResource<RouteConfiguration>>> routesByGroup;
        if (routeUpdated) {
            routes = CentralDogmaSnapshotResources.create(routeResources, ResourceType.ROUTE);
            routesByGroup = immutableByGroup(routeResources);
            routeUpdated = false;
        } else {
            routes = prevSnapshot.routes();
            routesByGroup = prev.routesByGroup;
        }

        final CentralDogmaSnapshot snapshot =
                new CentralDogmaSnapshot(clusters, endpoints, listeners, routes, prevSnapshot.secrets());
        state = new XdsState(snapshot, clustersByGroup, listenersByGroup, routesByGroup);
        return snapshot;
    }

    /**
     * Builds a snapshot containing only the resources of the specified {@code groups}. The cost is proportional
     * to the number of resources in {@code groups}, not to the total number of resources, because the resources
     * are assembled from the per-group index instead of scanning every group.
     */
    CentralDogmaSnapshot snapshot(Set<String> groups) {
        // Read the whole state once so the snapshot and the per-group indexes are from the same revision.
        final XdsState current = state;
        return new CentralDogmaSnapshot(
                collectByGroups(current.clustersByGroup, groups, ResourceType.CLUSTER),
                // Endpoints (EDS) are not access-controlled: every client can read the endpoints of all groups
                // regardless of its READ access, so they are reused unfiltered.
                current.snapshot.endpoints(),
                collectByGroups(current.listenersByGroup, groups, ResourceType.LISTENER),
                collectByGroups(current.routesByGroup, groups, ResourceType.ROUTE),
                current.snapshot.secrets());
    }

    private static <T extends Message> SnapshotResources<T> collectByGroups(
            Map<String, Map<String, VersionedResource<T>>> resourcesByGroup, Set<String> groups,
            ResourceType resourceType) {
        final ImmutableMap.Builder<String, VersionedResource<T>> collected = ImmutableMap.builder();
        for (String group : groups) {
            final Map<String, VersionedResource<T>> groupResources = resourcesByGroup.get(group);
            if (groupResources != null) {
                // Resource names are namespaced as "groups/{group}/...", so there is no key collision across
                // groups.
                collected.putAll(groupResources);
            }
        }
        return CentralDogmaSnapshotResources.createFlat(collected.build(), resourceType);
    }

    private static <T extends Message> Map<String, Map<String, VersionedResource<T>>> immutableByGroup(
            Map<String, Map<String, VersionedResource<T>>> resourcesByGroup) {
        final ImmutableMap.Builder<String, Map<String, VersionedResource<T>>> copy = ImmutableMap.builder();
        resourcesByGroup.forEach((group, resources) -> copy.put(group, ImmutableMap.copyOf(resources)));
        return copy.build();
    }

    void removeGroup(String groupName) {
        clusterUpdated |= clusterResources.remove(groupName) != null;
        endpointUpdated |= endpointResources.remove(groupName) != null;
        listenerUpdated |= listenerResources.remove(groupName) != null;
        routeUpdated |= routeResources.remove(groupName) != null;
    }

    /**
     * The full snapshot together with its per-group indexes, published as a single immutable unit so that
     * {@link #snapshot(Set)} always reads a consistent revision.
     */
    private static final class XdsState {

        private final CentralDogmaSnapshot snapshot;
        // Per-group views of the access-controlled resources, consistent with snapshot. Endpoints are not
        // indexed because they are served unfiltered to every client.
        private final Map<String, Map<String, VersionedResource<Cluster>>> clustersByGroup;
        private final Map<String, Map<String, VersionedResource<Listener>>> listenersByGroup;
        private final Map<String, Map<String, VersionedResource<RouteConfiguration>>> routesByGroup;

        XdsState(CentralDogmaSnapshot snapshot,
                 Map<String, Map<String, VersionedResource<Cluster>>> clustersByGroup,
                 Map<String, Map<String, VersionedResource<Listener>>> listenersByGroup,
                 Map<String, Map<String, VersionedResource<RouteConfiguration>>> routesByGroup) {
            this.snapshot = snapshot;
            this.clustersByGroup = clustersByGroup;
            this.listenersByGroup = listenersByGroup;
            this.routesByGroup = routesByGroup;
        }
    }
}
