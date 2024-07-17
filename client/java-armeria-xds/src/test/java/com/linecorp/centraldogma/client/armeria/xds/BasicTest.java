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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.listener.v3.Listener;

class BasicTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
        }
    };

    @RegisterExtension
    static CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("foo").join();
            client.createRepository("foo", "bar")
                  .join()
                  .commit("Initial file", Change.ofJsonUpsert("/foo.json", "{ \"a\": \"bar\" }"))
                  .push()
                  .join();
        }
    };

    @BeforeEach
    void beforeEach() {
        final Listener listener = XdsResourceReader.readResourcePath(
                "/test-listener.yaml",
                Listener.newBuilder(),
                ImmutableMap.of("<LISTENER_NAME>", XdsCentralDogmaBuilder.LISTENER_NAME,
                                "<CLUSTER_NAME>", "my-cluster"));
        final Cluster cluster = XdsResourceReader.readResourcePath(
                "/test-cluster.yaml",
                Cluster.newBuilder(),
                ImmutableMap.of("<NAME>", "my-cluster", "<TYPE>", "STATIC",
                                "<PORT>", dogma.serverAddress().getPort()));
        cache.setSnapshot(
                GROUP,
                Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(),
                                ImmutableList.of(listener), ImmutableList.of(), ImmutableList.of(), "1"));
    }

    @Test
    void basicCase() throws Exception {
        try (CentralDogma client = new XdsCentralDogmaBuilder().host("127.0.0.1", server.httpPort()).build()) {
            final Entry<JsonNode> entry = client.forRepo("foo", "bar")
                                                .file(Query.ofJsonPath("/foo.json"))
                                                .get()
                                                .get();
            assertThatJson(entry.content()).node("a").isStringEqualTo("bar");
        }
    }
}
