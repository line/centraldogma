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

import java.util.Map;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import io.envoyproxy.controlplane.cache.SnapshotResources;
import io.envoyproxy.controlplane.cache.VersionedResource;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;

final class CentralDogmaSnapshot extends Snapshot {

    private final SnapshotResources<Cluster> clusters;
    private final SnapshotResources<ClusterLoadAssignment> endpoints;
    private final SnapshotResources<Listener> listeners;
    private final SnapshotResources<RouteConfiguration> routes;
    private final SnapshotResources<Secret> secrets;

    CentralDogmaSnapshot(SnapshotResources<Cluster> clusters,
                         SnapshotResources<ClusterLoadAssignment> endpoints,
                         SnapshotResources<Listener> listeners, SnapshotResources<RouteConfiguration> routes,
                         SnapshotResources<Secret> secrets) {
        this.clusters = clusters;
        this.endpoints = endpoints;
        this.listeners = listeners;
        this.routes = routes;
        this.secrets = secrets;
    }

    @Override
    public SnapshotResources<Cluster> clusters() {
        return clusters;
    }

    @Override
    public SnapshotResources<ClusterLoadAssignment> endpoints() {
        return endpoints;
    }

    @Override
    public SnapshotResources<Listener> listeners() {
        return listeners;
    }

    @Override
    public SnapshotResources<RouteConfiguration> routes() {
        return routes;
    }

    @Override
    public SnapshotResources<Secret> secrets() {
        return secrets;
    }

    @Override
    public Map<String, VersionedResource<?>> versionedResources(ResourceType resourceType) {
        // Have to override this method because of the type inference.
        return super.versionedResources(resourceType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CentralDogmaSnapShot)) {
            return false;
        }
        final CentralDogmaSnapShot that = (CentralDogmaSnapShot) o;
        return Objects.equal(clusters, that.clusters) &&
               Objects.equal(endpoints, that.endpoints) &&
               Objects.equal(listeners, that.listeners) &&
               Objects.equal(routes, that.routes) &&
               Objects.equal(secrets, that.secrets);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(clusters, endpoints, listeners, routes, secrets);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("clusters", clusters)
                          .add("endpoints", endpoints)
                          .add("listeners", listeners)
                          .add("routes", routes)
                          .add("secrets", secrets)
                          .toString();
    }
}
