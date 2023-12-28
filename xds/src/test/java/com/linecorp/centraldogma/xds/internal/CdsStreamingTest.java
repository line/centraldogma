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

import static com.linecorp.centraldogma.server.internal.storage.project.ProjectInitializer.INTERNAL_PROJECT_DOGMA;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.CLUSTER_FILE;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.CLUSTER_REPO;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.GrpcService.EnvoyGrpc;
import io.envoyproxy.envoy.service.cluster.v3.ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceStub;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.stub.StreamObserver;

@Testcontainers(disabledWithoutDocker = true)
final class CdsStreamingTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    private static Cluster cluster(String clusterName, long connectTimeout) {
        final GrpcService.Builder grpcServiceBuilder =
                GrpcService.newBuilder().setEnvoyGrpc(
                        EnvoyGrpc.newBuilder()
                                 .setClusterName("whatever"));

        final ConfigSource edsConfigSource =
                ConfigSource.newBuilder()
                            .setResourceApiVersion(ApiVersion.V3)
                            .setApiConfigSource(
                                    ApiConfigSource.newBuilder()
                                                   .setTransportApiVersion(ApiVersion.V3)
                                                   .setApiType(ApiConfigSource.ApiType.GRPC)
                                                   .addGrpcServices(grpcServiceBuilder))
                            .build();

        return Cluster.newBuilder()
                      .setName(clusterName)
                      .setType(DiscoveryType.EDS)
                      .setEdsClusterConfig(EdsClusterConfig.newBuilder()
                                                           .setEdsConfig(edsConfigSource))
                      .setConnectTimeout(Duration.newBuilder().setSeconds(connectTimeout))
                      .build();
    }

    static void commit(MessageOrBuilder message, ProjectManager projectManager,
                       String repoName, String clusterName, String fileName) {
        final String json;
        try {
            json = JsonFormatUtil.printer().print(message);
        } catch (InvalidProtocolBufferException e) {
            // Should never reach here.
            throw new Error(e);
        }
        final Change<JsonNode> echoCluster =
                Change.ofJsonUpsert('/' + clusterName + '/' + fileName, json);
        projectManager.get(INTERNAL_PROJECT_DOGMA)
                      .repos()
                      .get(repoName)
                      .commit(Revision.HEAD, 0, Author.SYSTEM, "Add " + clusterName, echoCluster).join();
    }

    @Test
    void cdsStream() throws Exception {
        final String fooClusterName = "foo/cluster";
        Cluster fooCluster = cluster(fooClusterName, 1);
        commit(fooCluster, dogma.projectManager(), CLUSTER_REPO, fooClusterName, CLUSTER_FILE);
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

        fooCluster = cluster(fooClusterName, 2); // Change the configuration.
        commit(fooCluster, dogma.projectManager(), CLUSTER_REPO, fooClusterName, CLUSTER_FILE);
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
        final Cluster fooBarCluster = cluster(fooBarClusterName, 2);// Change the configuration.
        commit(fooBarCluster, dogma.projectManager(), CLUSTER_REPO, fooBarClusterName, CLUSTER_FILE);
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
