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

import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createClusterAndCommit;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.google.protobuf.Any;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.service.cluster.v3.ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceStub;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.stub.StreamObserver;

@Testcontainers(disabledWithoutDocker = true)
final class CdsStreamingTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @Test
    void cdsStream() throws Exception {
        final String fooClusterName = "foo/cluster";
        Cluster fooCluster = createClusterAndCommit(fooClusterName, 1, dogma.projectManager());
        final ClusterDiscoveryServiceStub client = GrpcClients.newClient(
                dogma.httpClient().uri(), ClusterDiscoveryServiceStub.class);
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
        assertThat(discoveryResponse.getVersionInfo()).isEqualTo("2");
        assertThat(discoveryResponse.getNonce()).isEqualTo("0");
        List<Any> resources = discoveryResponse.getResourcesList();
        assertThat(resources.size()).isOne();
        Any any = resources.get(0);
        assertThat(any.getTypeUrl()).isEqualTo(Resources.V3.CLUSTER_TYPE_URL);
        assertThat(fooCluster).isEqualTo(Cluster.parseFrom(any.getValue()));
        // No more discovery response.
        assertThat(queue.poll(300, TimeUnit.MILLISECONDS)).isNull();
        // Send ack
        sendAck(requestStreamObserver, discoveryResponse);
        // No discovery response because there's no change.
        assertThat(queue.poll(300, TimeUnit.MILLISECONDS)).isNull();

        // Change the configuration.
        fooCluster = createClusterAndCommit(fooClusterName, 2, dogma.projectManager());
        discoveryResponse = queue.take();
        assertThat(discoveryResponse.getVersionInfo()).isEqualTo("3");
        assertThat(discoveryResponse.getNonce()).isEqualTo("1");
        resources = discoveryResponse.getResourcesList();
        assertThat(resources.size()).isOne();
        any = resources.get(0);
        assertThat(any.getTypeUrl()).isEqualTo(Resources.V3.CLUSTER_TYPE_URL);
        assertThat(fooCluster).isEqualTo(Cluster.parseFrom(any.getValue()));
        // No more discovery response.
        assertThat(queue.poll(300, TimeUnit.MILLISECONDS)).isNull();
        // Send ack
        sendAck(requestStreamObserver, discoveryResponse);
        // No discovery response because there's no change.
        assertThat(queue.poll(300, TimeUnit.MILLISECONDS)).isNull();

        // Add another cluster
        final String fooBarClusterName = "foo/bar/cluster";
        // Change the configuration.
        final Cluster fooBarCluster = createClusterAndCommit(fooBarClusterName, 2, dogma.projectManager());
        discoveryResponse = queue.take();
        assertThat(discoveryResponse.getVersionInfo()).isEqualTo("4");
        assertThat(discoveryResponse.getNonce()).isEqualTo("2");
        resources = discoveryResponse.getResourcesList();
        assertThat(resources.size()).isEqualTo(2);
        any = resources.get(0);
        assertThat(any.getTypeUrl()).isEqualTo(Resources.V3.CLUSTER_TYPE_URL);
        assertThat(fooBarCluster).isEqualTo(Cluster.parseFrom(any.getValue()));

        any = resources.get(1);
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
