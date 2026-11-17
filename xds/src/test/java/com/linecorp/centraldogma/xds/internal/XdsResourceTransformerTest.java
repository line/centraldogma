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

import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.cluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createCluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.updateCluster;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.Any;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.cluster.v3.ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceStub;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.stub.StreamObserver;

final class XdsResourceTransformerTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @Test
    void clusterIsTransformedBasedOnNodeMetadata() throws Exception {
        final String groupName = "groups/transformer-test";
        final WebClient webClient = dogma.httpClient();
        createGroup("transformer-test", webClient);

        final String clusterId = "my-cluster";
        final String clusterName = groupName + "/clusters/" + clusterId;
        final Cluster cluster = cluster(clusterName, 1);
        createCluster(groupName, clusterId, cluster, webClient);

        // Build a Node with alt_stat_suffix metadata so TestXdsResourceTransformer kicks in.
        final Node node = Node.newBuilder()
                              .setId("test-node")
                              .setMetadata(Struct.newBuilder()
                                                 .putFields("alt_stat_suffix",
                                                            Value.newBuilder()
                                                                 .setStringValue("canary")
                                                                 .build()))
                              .build();

        final ClusterDiscoveryServiceStub client = GrpcClients.newClient(
                webClient.uri(), ClusterDiscoveryServiceStub.class);
        final BlockingQueue<DiscoveryResponse> queue = new ArrayBlockingQueue<>(2);
        final StreamObserver<DiscoveryRequest> requestStream = client.streamClusters(
                new StreamObserver<>() {
                    @Override
                    public void onNext(DiscoveryResponse value) {
                        queue.add(value);
                    }

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {}
                });

        // Send the initial DiscoveryRequest with the node.
        requestStream.onNext(DiscoveryRequest.newBuilder()
                                             .setNode(node)
                                             .setTypeUrl(Resources.V3.CLUSTER_TYPE_URL)
                                             .build());

        DiscoveryResponse response = queue.take();
        while (response.getResourcesList().isEmpty()) {
            sendAck(requestStream, response, node);
            response = queue.take();
        }

        // Verify the transformer set altStatName.
        final List<Any> resources = response.getResourcesList();
        assertThat(resources).isNotEmpty();
        Cluster received = findCluster(resources, clusterName);
        assertThat(received.getAltStatName()).isEqualTo(clusterName + "-canary");

        // ACK and verify no spurious updates.
        sendAck(requestStream, response, node);
        assertThat(queue.poll(300, TimeUnit.MILLISECONDS)).isNull();

        // Update the cluster and verify the transformer still applies.
        final Cluster updatedCluster = cluster(clusterName, 2);
        updateCluster(groupName, clusterId, updatedCluster, webClient);

        response = queue.take();
        final List<Any> updatedResources = response.getResourcesList();
        assertThat(updatedResources).isNotEmpty();
        received = findCluster(updatedResources, clusterName);
        assertThat(received.getAltStatName()).isEqualTo(clusterName + "-canary");
        // The connect timeout should reflect the updated value.
        assertThat(received.getConnectTimeout().getSeconds()).isEqualTo(2);
    }

    @Test
    void clusterIsNotTransformedWithoutMetadata() throws Exception {
        final String groupName = "groups/no-transform-test";
        final WebClient webClient = dogma.httpClient();
        createGroup("no-transform-test", webClient);

        final String clusterId = "plain-cluster";
        final String clusterName = groupName + "/clusters/" + clusterId;
        final Cluster cluster = cluster(clusterName, 1);
        createCluster(groupName, clusterId, cluster, webClient);

        // Node without alt_stat_suffix metadata — transformer should be a no-op.
        final Node node = Node.newBuilder()
                              .setId("plain-node")
                              .build();

        final ClusterDiscoveryServiceStub client = GrpcClients.newClient(
                webClient.uri(), ClusterDiscoveryServiceStub.class);
        final BlockingQueue<DiscoveryResponse> queue = new ArrayBlockingQueue<>(2);
        final StreamObserver<DiscoveryRequest> requestStream = client.streamClusters(
                new StreamObserver<DiscoveryResponse>() {
                    @Override
                    public void onNext(DiscoveryResponse value) {
                        queue.add(value);
                    }

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {}
                });

        requestStream.onNext(DiscoveryRequest.newBuilder()
                                             .setNode(node)
                                             .setTypeUrl(Resources.V3.CLUSTER_TYPE_URL)
                                             .build());

        DiscoveryResponse response = queue.take();
        while (response.getResourcesList().isEmpty()) {
            sendAck(requestStream, response, node);
            response = queue.take();
        }

        final Cluster received = findCluster(response.getResourcesList(), clusterName);
        // altStatName should be empty because there's no alt_stat_suffix metadata.
        assertThat(received.getAltStatName()).isEmpty();
    }

    private static Cluster findCluster(List<Any> resources, String clusterName) throws Exception {
        for (Any any : resources) {
            final Cluster c = Cluster.parseFrom(any.getValue());
            if (clusterName.equals(c.getName())) {
                return c;
            }
        }
        throw new AssertionError("Cluster not found: " + clusterName);
    }

    private static void sendAck(StreamObserver<DiscoveryRequest> requestStream,
                                DiscoveryResponse response, Node node) {
        requestStream.onNext(DiscoveryRequest.newBuilder()
                                             .setNode(node)
                                             .setTypeUrl(Resources.V3.CLUSTER_TYPE_URL)
                                             .setVersionInfo(response.getVersionInfo())
                                             .setResponseNonce(response.getNonce())
                                             .build());
    }
}
