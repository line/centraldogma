/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.centraldogma.client.armeria.xds;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

class XdsResourceReaderTest {

    @Test
    void basicCase() throws Exception {
        final Listener listener = XdsResourceReader.readResourcePath(
                "/test-listener.yaml",
                Listener.newBuilder(),
                ImmutableMap.of("<LISTENER_NAME>", "listener_0", "<CLUSTER_NAME>", "my-cluster"));
        assertThat(listener.getName()).isEqualTo("listener_0");
        final HttpConnectionManager manager = listener.getApiListener().getApiListener()
                                                      .unpack(HttpConnectionManager.class);
        assertThat(manager.getRouteConfig().getName()).isEqualTo("local_route");
        assertThat(manager.getRouteConfig().getVirtualHosts(0).getRoutes(0)
                          .getRoute().getCluster()).isEqualTo("my-cluster");
    }

    @Test
    void clusterReplacements() throws Exception {
        final Cluster cluster = XdsResourceReader.readResourcePath(
                "/test-cluster.yaml",
                Cluster.newBuilder(),
                ImmutableMap.of("<NAME>", "test-cluster", "<TYPE>", "EDS", "<PORT>", "8080"));
        assertThat(cluster.getName()).isEqualTo("test-cluster");
        assertThat(cluster.getType()).isEqualTo(DiscoveryType.EDS);
        final ClusterLoadAssignment loadAssignment = cluster.getLoadAssignment();
        assertThat(loadAssignment.getClusterName()).isEqualTo("test-cluster");
        assertThat(loadAssignment.getEndpoints(0).getLbEndpoints(0).getEndpoint().getAddress()
                                 .getSocketAddress().getPortValue())
                .isEqualTo(8080);
    }
}
