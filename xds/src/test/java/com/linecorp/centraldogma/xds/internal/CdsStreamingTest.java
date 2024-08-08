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

import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.cluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createCluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.deleteGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.updateCluster;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.service.cluster.v3.ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceStub;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.stub.StreamObserver;

final class CdsStreamingTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @Test
    void cdsStream() throws Exception {
        final String fooGroupName = "groups/foo";
        final WebClient webClient = dogma.httpClient();
        createGroup(fooGroupName, webClient);

        final String fooClusterId = "foo-cluster";
        final String fooClusterName = fooGroupName + "/clusters/" + fooClusterId;
        Cluster fooCluster = cluster(fooClusterName, 1);
        createCluster(fooGroupName, fooClusterId, fooCluster, webClient);

        final ClusterDiscoveryServiceStub client = GrpcClients.newClient(
                webClient.uri(), ClusterDiscoveryServiceStub.class);
        final BlockingQueue<DiscoveryResponse> queue = new ArrayBlockingQueue<>(2);
        final StreamObserver<DiscoveryRequest> requestStreamObserver = client.streamClusters(
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
        requestStreamObserver.onNext(DiscoveryRequest.newBuilder()
                                                     .setTypeUrl(Resources.V3.CLUSTER_TYPE_URL)
                                                     .build());
        DiscoveryResponse discoveryResponse = queue.take();
        final String versionInfo1 = discoveryResponse.getVersionInfo();
        assertDiscoveryResponse(versionInfo1, discoveryResponse, fooCluster, queue, "0");
        // Send ack
        sendAck(requestStreamObserver, discoveryResponse);
        // No discovery response because there's no change.
        assertThat(queue.poll(300, TimeUnit.MILLISECONDS)).isNull();

        // Change the configuration.
        fooCluster = cluster(fooClusterName, 2);
        updateCluster(fooGroupName, fooClusterId, fooCluster, webClient);
        discoveryResponse = queue.take();
        final String versionInfo2 = discoveryResponse.getVersionInfo();
        assertThat(versionInfo2).isNotEqualTo(versionInfo1);
        assertDiscoveryResponse(versionInfo2, discoveryResponse, fooCluster, queue, "1");
        // Send ack
        sendAck(requestStreamObserver, discoveryResponse);
        // No discovery response because there's no change.
        assertThat(queue.poll(300, TimeUnit.MILLISECONDS)).isNull();

        // Add another cluster
        final String barGroupName = "groups/bar";
        createGroup(barGroupName, webClient);
        final String barClusterId = "bar-cluster";
        final String barClusterName = barGroupName + "/clusters/" + barClusterId;
        final Cluster barCluster = cluster(barClusterName, 1);
        createCluster(barGroupName, barClusterId, barCluster, webClient);

        discoveryResponse = queue.take();
        final String versionInfo3 = discoveryResponse.getVersionInfo();
        assertThat(versionInfo3.length()).isEqualTo(64);
        assertThat(versionInfo3).isNotEqualTo(versionInfo2);
        final List<Any> resources = discoveryResponse.getResourcesList();
        assertThat(resources.size()).isEqualTo(2);
        Any any = resources.get(0);
        assertThat(any.getTypeUrl()).isEqualTo(Resources.V3.CLUSTER_TYPE_URL);
        assertThat(barCluster).isEqualTo(Cluster.parseFrom(any.getValue()));

        any = resources.get(1);
        assertThat(any.getTypeUrl()).isEqualTo(Resources.V3.CLUSTER_TYPE_URL);
        assertThat(fooCluster).isEqualTo(Cluster.parseFrom(any.getValue()));

        // Send ack
        sendAck(requestStreamObserver, discoveryResponse);
        // No more discovery response.
        assertThat(queue.poll(300, TimeUnit.MILLISECONDS)).isNull();

        // Remove bar group.
        deleteGroup(barGroupName, webClient);
        discoveryResponse = queue.take();
        final String versionInfo4 = discoveryResponse.getVersionInfo();
        assertDiscoveryResponse(versionInfo4, discoveryResponse, fooCluster, queue, "3");
        assertThat(versionInfo4).isEqualTo(versionInfo2);
        // Send ack
        sendAck(requestStreamObserver, discoveryResponse);
        // No discovery response because there's no change.
        assertThat(queue.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private static void assertDiscoveryResponse(
            String versionInfo, DiscoveryResponse discoveryResponse,
            Cluster fooCluster, BlockingQueue<DiscoveryResponse> queue, String nonce)
            throws InvalidProtocolBufferException, InterruptedException {
        assertThat(versionInfo.length()).isEqualTo(64); // sha 256 hash length is 64. 256/4
        assertThat(discoveryResponse.getNonce()).isEqualTo(nonce);
        final List<Any> resources = discoveryResponse.getResourcesList();
        assertThat(resources.size()).isOne();
        final Any any = resources.get(0);
        assertThat(any.getTypeUrl()).isEqualTo(Resources.V3.CLUSTER_TYPE_URL);
        assertThat(fooCluster).isEqualTo(Cluster.parseFrom(any.getValue()));
        // No more discovery response.
        assertThat(queue.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private static void sendAck(StreamObserver<DiscoveryRequest> requestStreamObserver,
                                DiscoveryResponse discoveryResponse) {
        requestStreamObserver.onNext(DiscoveryRequest.newBuilder()
                                                     .setTypeUrl(Resources.V3.CLUSTER_TYPE_URL)
                                                     .setVersionInfo(discoveryResponse.getVersionInfo())
                                                     .setResponseNonce(discoveryResponse.getNonce())
                                                     .build());
    }
}
