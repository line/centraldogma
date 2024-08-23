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
package com.linecorp.centraldogma.xds.cluster.v1;

import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.cluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createCluster;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.updateCluster;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.Empty;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;
import com.linecorp.centraldogma.xds.cluster.v1.XdsClusterServiceGrpc.XdsClusterServiceBlockingStub;

import io.envoyproxy.controlplane.cache.Resources.V3;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.service.cluster.v3.ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceStub;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

class XdsClusterServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @BeforeAll
    static void setup() {
        final AggregatedHttpResponse response = createGroup("foo", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.OK);
    }

    @Test
    void createClusterViaHttp() throws Exception {
        final Cluster cluster = cluster("this_cluster_name_will_be_ignored_and_replaced", 1);
        AggregatedHttpResponse response = createCluster("groups/foo", "@invalid_cluster_id", cluster,
                                                        dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.BAD_REQUEST);

        response = createCluster("groups/non-existent-group", "foo-cluster/1", cluster, dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        response = createCluster("groups/foo", "foo-cluster/1", cluster, dogma.httpClient());
        assertOk(response);
        final Cluster.Builder clusterBuilder = Cluster.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(response.contentUtf8(), clusterBuilder);
        final Cluster actualCluster = clusterBuilder.build();
        final String clusterName = "groups/foo/clusters/foo-cluster/1";
        assertThat(actualCluster).isEqualTo(cluster.toBuilder().setName(clusterName).build());
        checkResourceViaDiscoveryRequest(actualCluster, clusterName, true);

        // Create the same cluster again.
        response = createCluster("groups/foo", "foo-cluster/1", cluster, dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.CONFLICT);
        assertThat(response.headers().get("grpc-status"))
                .isEqualTo(Integer.toString(Status.ALREADY_EXISTS.getCode().value()));
    }

    private static void assertOk(AggregatedHttpResponse response) {
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.headers().get("grpc-status")).isEqualTo("0");
    }

    private static void checkResourceViaDiscoveryRequest(Cluster actualCluster, String resourceName,
                                                         boolean created) {
        await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(
                () -> checkResourceViaDiscoveryRequest0(actualCluster, resourceName, created));
    }

    private static void checkResourceViaDiscoveryRequest0(Cluster actualCluster, String resourceName,
                                                          boolean created)
            throws InterruptedException, InvalidProtocolBufferException {
        final ClusterDiscoveryServiceStub client = GrpcClients.newClient(dogma.httpClient().uri(),
                                                                         ClusterDiscoveryServiceStub.class);
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
        requestStreamObserver.onNext(DiscoveryRequest.newBuilder().setTypeUrl(V3.CLUSTER_TYPE_URL)
                                                     .addResourceNames(resourceName).build());
        if (created) {
            final DiscoveryResponse discoveryResponse = queue.take();
            final List<Any> resources = discoveryResponse.getResourcesList();
            assertThat(resources.size()).isOne();
            final Any any = resources.get(0);
            assertThat(any.getTypeUrl()).isEqualTo(V3.CLUSTER_TYPE_URL);
            assertThat(actualCluster).isEqualTo(Cluster.parseFrom(any.getValue()));
        } else {
            final DiscoveryResponse discoveryResponse = queue.poll(300, TimeUnit.MILLISECONDS);
            assertThat(discoveryResponse).isNull();
        }
    }

    @Test
    void updateClusterViaHttp() throws Exception {
        final Cluster cluster = cluster("this_cluster_name_will_be_ignored_and_replaced", 1);
        AggregatedHttpResponse response = updateCluster("groups/foo", "foo-cluster/2",
                                                        cluster, dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        response = createCluster("groups/foo", "foo-cluster/2", cluster, dogma.httpClient());
        assertOk(response);
        final Cluster.Builder clusterBuilder = Cluster.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(response.contentUtf8(), clusterBuilder);
        final Cluster actualCluster = clusterBuilder.build();
        final String clusterName = "groups/foo/clusters/foo-cluster/2";
        assertThat(actualCluster).isEqualTo(cluster.toBuilder().setName(clusterName).build());
        checkResourceViaDiscoveryRequest(actualCluster, clusterName, true);

        final Cluster updatingCluster = cluster.toBuilder().setConnectTimeout(
                Duration.newBuilder().setSeconds(2).build()).setName(clusterName).build();
        response = updateCluster("groups/foo", "foo-cluster/2", updatingCluster, dogma.httpClient());
        assertOk(response);
        final Cluster.Builder clusterBuilder2 = Cluster.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(response.contentUtf8(), clusterBuilder2);
        final Cluster actualCluster2 = clusterBuilder2.build();
        assertThat(actualCluster2).isEqualTo(updatingCluster.toBuilder().setName(clusterName).build());
        checkResourceViaDiscoveryRequest(actualCluster2, clusterName, true);

        // Can update with the same cluster again.
        response = updateCluster("groups/foo", "foo-cluster/2", updatingCluster, dogma.httpClient());
        assertOk(response);
    }

    @Test
    void deleteClusterViaHttp() throws Exception {
        final String clusterName = "groups/foo/clusters/foo-cluster/3/4";
        AggregatedHttpResponse response = deleteCluster(clusterName);
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        final Cluster cluster = cluster("this_cluster_name_will_be_ignored_and_replaced", 1);
        response = createCluster("groups/foo", "foo-cluster/3/4", cluster, dogma.httpClient());
        assertOk(response);

        final Cluster actualCluster = cluster.toBuilder().setName(clusterName).build();
        checkResourceViaDiscoveryRequest(actualCluster, clusterName, true);

        // Add permission test.

        response = deleteCluster(clusterName);
        assertOk(response);
        assertThat(response.contentUtf8()).isEqualTo("{}");
        checkResourceViaDiscoveryRequest(actualCluster, clusterName, false);
    }

    private static AggregatedHttpResponse deleteCluster(String clusterName) {
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.DELETE, "/api/v1/xds/" + clusterName)
                                                     .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                                                     .build();
        return dogma.httpClient().execute(headers).aggregate().join();
    }

    @Test
    void viaStub() {
        final XdsClusterServiceBlockingStub client = GrpcClients.builder(dogma.httpClient().uri()).setHeader(
                HttpHeaderNames.AUTHORIZATION, "Bearer anonymous").build(XdsClusterServiceBlockingStub.class);
        final Cluster cluster = cluster("this_cluster_name_will_be_ignored_and_replaced", 1);
        Cluster response = client.createCluster(CreateClusterRequest.newBuilder().setParent("groups/foo")
                                                                    .setClusterId("foo-cluster/5/6")
                                                                    .setCluster(cluster).build());
        final String clusterName = "groups/foo/clusters/foo-cluster/5/6";
        assertThat(response).isEqualTo(cluster.toBuilder().setName(clusterName).build());

        final Cluster updatingCluster = cluster.toBuilder().setConnectTimeout(
                Duration.newBuilder().setSeconds(2).build()).setName(clusterName).build();
        response = client.updateCluster(UpdateClusterRequest.newBuilder().setCluster(updatingCluster).build());
        assertThat(response).isEqualTo(updatingCluster);

        // No exception is thrown.
        final Empty ignored = client.deleteCluster(
                DeleteClusterRequest.newBuilder().setName(clusterName).build());
    }
}
