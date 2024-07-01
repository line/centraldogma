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

    void setCluster(String projectName, Cluster cluster) {
        final Map<String, VersionedResource<Cluster>> projectClusters =
                clusterResources.computeIfAbsent(projectName, k -> new HashMap<>());
        projectClusters.put(cluster.getName(), VersionedResource.create(cluster));
        clusterUpdated = true;
    }

    void setEndpoint(String projectName, ClusterLoadAssignment endpoint) {
        final Map<String, VersionedResource<ClusterLoadAssignment>> projectEndpoints =
                endpointResources.computeIfAbsent(projectName, k -> new HashMap<>());
        projectEndpoints.put(endpoint.getClusterName(), VersionedResource.create(endpoint));
        endpointUpdated = true;
    }

    void setListener(String projectName, Listener listener) {
        final Map<String, VersionedResource<Listener>> projectListeners =
                listenerResources.computeIfAbsent(projectName, k -> new HashMap<>());
        projectListeners.put(listener.getName(), VersionedResource.create(listener));
        listenerUpdated = true;
    }

    void setRoute(String projectName, RouteConfiguration route) {
        final Map<String, VersionedResource<RouteConfiguration>> projectRoutes =
                routeResources.computeIfAbsent(projectName, k -> new HashMap<>());
        projectRoutes.put(route.getName(), VersionedResource.create(route));
        routeUpdated = true;
    }

    void removeCluster(String projectName, String path) {
        final Map<String, VersionedResource<Cluster>> projectClusters = clusterResources.get(projectName);
        if (projectClusters == null) {
            return;
        }
        clusterUpdated |= projectClusters.remove(path) != null;
    }

    void removeEndpoint(String projectName, String path) {
        final Map<String, VersionedResource<ClusterLoadAssignment>> projectEndpoints =
                endpointResources.get(projectName);
        if (projectEndpoints == null) {
            return;
        }
        endpointUpdated |= projectEndpoints.remove(path) != null;
    }

    void removeListener(String projectName, String path) {
        final Map<String, VersionedResource<Listener>> projectListeners = listenerResources.get(projectName);
        if (projectListeners == null) {
            return;
        }
        listenerUpdated |= projectListeners.remove(path) != null;
    }

    void removeRoute(String projectName, String path) {
        final Map<String, VersionedResource<RouteConfiguration>> projectRoutes =
                routeResources.get(projectName);
        if (projectRoutes == null) {
            return;
        }
        routeUpdated |= projectRoutes.remove(path) != null;
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

    void removeProject(String projectName) {
        clusterUpdated |= clusterResources.remove(projectName) != null;
        endpointUpdated |= endpointResources.remove(projectName) != null;
        listenerUpdated |= listenerResources.remove(projectName) != null;
        routeUpdated |= routeResources.remove(projectName) != null;
    }
}
