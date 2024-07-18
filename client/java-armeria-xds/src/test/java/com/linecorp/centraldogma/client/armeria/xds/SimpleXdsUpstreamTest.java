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
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.listener.v3.Listener;

class SimpleXdsUpstreamTest {

    private static final AtomicLong VERSION_NUMBER = new AtomicLong();
    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.port(0, SessionProtocol.HTTP);
            sb.port(0, SessionProtocol.HTTP);
            sb.port(0, SessionProtocol.HTTP);
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
        protected void configure(CentralDogmaBuilder builder) {
            builder.port(0, SessionProtocol.HTTP);
            builder.port(0, SessionProtocol.HTTP);
        }

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

    @Test
    void singleBootstrapSingleUpstream() throws Exception {
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
                                ImmutableList.of(listener), ImmutableList.of(), ImmutableList.of(),
                                String.valueOf(VERSION_NUMBER.incrementAndGet())));

        try (CentralDogma client = new XdsCentralDogmaBuilder().host("127.0.0.1", server.httpPort()).build()) {
            final Entry<JsonNode> entry = client.forRepo("foo", "bar")
                                                .file(Query.ofJsonPath("/foo.json"))
                                                .get()
                                                .get();
            assertThatJson(entry.content()).node("a").isStringEqualTo("bar");
        }
    }

    @Test
    void multiBootstrapMultiUpstream() throws Exception {
        final List<Integer> dogmaPorts = dogma.dogma().activePorts().values().stream().map(
                port -> port.localAddress().getPort()).collect(Collectors.toList());
        final List<Integer> serverPorts = server.server().activePorts().values().stream().map(
                port -> port.localAddress().getPort()).collect(Collectors.toList());
        assertThat(dogmaPorts).hasSize(3);

        final Listener listener = XdsResourceReader.readResourcePath(
                "/test-listener.yaml",
                Listener.newBuilder(),
                ImmutableMap.of("<LISTENER_NAME>", XdsCentralDogmaBuilder.LISTENER_NAME,
                                "<CLUSTER_NAME>", "my-cluster"));
        final Cluster cluster = XdsResourceReader.readResourcePath(
                "/test-cluster-multiendpoint.yaml",
                Cluster.newBuilder(),
                ImmutableMap.of("<NAME>", "my-cluster", "<TYPE>", "STATIC",
                                "<PORT1>", dogmaPorts.get(0),
                                "<PORT2>", dogmaPorts.get(1),
                                "<PORT3>", dogmaPorts.get(2)));
        cache.setSnapshot(
                GROUP,
                Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(),
                                ImmutableList.of(listener), ImmutableList.of(), ImmutableList.of(),
                                String.valueOf(VERSION_NUMBER.incrementAndGet())));

        final XdsCentralDogmaBuilder builder = new XdsCentralDogmaBuilder();
        for (Integer port : serverPorts) {
            builder.host("127.0.0.1", port);
        }
        final Set<Integer> selectedPorts = new HashSet<>();
        try (CentralDogma client = builder.build()) {
            await().untilAsserted(() -> assertThat(client.whenEndpointReady()).isDone());
            // RoundRobinStrategy guarantees that each port will be selected once
            for (int i = 0; i < dogmaPorts.size(); i++) {
                try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                    final Entry<JsonNode> entry = client.forRepo("foo", "bar")
                                                        .file(Query.ofJsonPath("/foo.json"))
                                                        .get()
                                                        .get();
                    assertThatJson(entry.content()).node("a").isStringEqualTo("bar");
                    final ClientRequestContext ctx = captor.get();
                    selectedPorts.add(ctx.endpoint().port());
                }
            }
        }
        assertThat(selectedPorts).containsExactlyInAnyOrderElementsOf(dogmaPorts);
    }
}
