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

import com.google.common.collect.ImmutableList;

import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import io.envoyproxy.controlplane.cache.SnapshotResources;
import io.envoyproxy.controlplane.cache.VersionedResource;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;

final class CentralDogmaXdsResources {

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

    private CentralDogmaSnapshot currentSnapshot;

    CentralDogmaXdsResources() {
        final SnapshotResources<?> emptyResources = SnapshotResources.create(ImmutableList.of(),
                                                                             "empty_resources");
        currentSnapshot = new CentralDogmaSnapshot((SnapshotResources<Cluster>) emptyResources,
                                                   (SnapshotResources<ClusterLoadAssignment>) emptyResources,
                                                   (SnapshotResources<Listener>) emptyResources,
                                                   (SnapshotResources<RouteConfiguration>) emptyResources,
                                                   (SnapshotResources<Secret>) emptyResources);
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
        endpointUpdated |= groupEndpoints.remove(getResourceName(groupName, path)) != null;
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

    CentralDogmaSnapshot snapshot() {
        final SnapshotResources<Cluster> clusters;
        if (clusterUpdated) {
            clusters = CentralDogmaSnapshotResources.create(clusterResources, ResourceType.CLUSTER);
            clusterUpdated = false;
        } else {
            clusters = currentSnapshot.clusters();
        }

        final SnapshotResources<ClusterLoadAssignment> endpoints;
        if (endpointUpdated) {
            endpoints = CentralDogmaSnapshotResources.create(endpointResources, ResourceType.ENDPOINT);
            endpointUpdated = false;
        } else {
            endpoints = currentSnapshot.endpoints();
        }

        final SnapshotResources<Listener> listeners;
        if (listenerUpdated) {
            listeners = CentralDogmaSnapshotResources.create(listenerResources, ResourceType.LISTENER);
            listenerUpdated = false;
        } else {
            listeners = currentSnapshot.listeners();
        }

        final SnapshotResources<RouteConfiguration> routes;
        if (routeUpdated) {
            routes = CentralDogmaSnapshotResources.create(routeResources, ResourceType.ROUTE);
            routeUpdated = false;
        } else {
            routes = currentSnapshot.routes();
        }

        return currentSnapshot =
                new CentralDogmaSnapshot(clusters, endpoints, listeners, routes, currentSnapshot.secrets());
    }

    void removeGroup(String groupName) {
        clusterUpdated |= clusterResources.remove(groupName) != null;
        endpointUpdated |= endpointResources.remove(groupName) != null;
        listenerUpdated |= listenerResources.remove(groupName) != null;
        routeUpdated |= routeResources.remove(groupName) != null;
    }
}
