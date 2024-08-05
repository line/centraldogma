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
package com.linecorp.centraldogma.xds.endpoint.v1;

import static com.linecorp.centraldogma.xds.internal.XdsServiceUtil.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createEndpoint;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.endpoint;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.loadAssignment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.Any;
import com.google.protobuf.Empty;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;
import com.linecorp.centraldogma.xds.endpoint.v1.XdsEndpointServiceGrpc.XdsEndpointServiceBlockingStub;

import io.envoyproxy.controlplane.cache.Resources.V3;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.service.endpoint.v3.EndpointDiscoveryServiceGrpc.EndpointDiscoveryServiceStub;
import io.grpc.stub.StreamObserver;

class XdsEndpointServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @BeforeAll
    static void setup() {
        final AggregatedHttpResponse response = createGroup("groups/foo", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.OK);
    }

    @Test
    void createEndpointViaHttp() throws Exception {
        final ClusterLoadAssignment endpoint = loadAssignment("this_endpoint_name_will_be_ignored_and_replaced",
                                                              "127.0.0.1", 8080);
        AggregatedHttpResponse response =
                createEndpoint("groups/foo", "@invalid_endpoint_id", endpoint, dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.BAD_REQUEST);

        response = createEndpoint("groups/non-existent-group", "foo-endpoint/1", endpoint, dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        response = createEndpoint("groups/foo", "foo-endpoint/1", endpoint, dogma.httpClient());
        assertOk(response);
        final ClusterLoadAssignment.Builder endpointBuilder = ClusterLoadAssignment.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(response.contentUtf8(), endpointBuilder);
        final ClusterLoadAssignment actualEndpoint = endpointBuilder.build();
        final String clusterName = "groups/foo/clusters/foo-endpoint/1";
        assertThat(actualEndpoint).isEqualTo(
                endpoint.toBuilder().setClusterName(clusterName).build());
        checkResourceViaDiscoveryRequest(actualEndpoint, clusterName);
    }

    private static void assertOk(AggregatedHttpResponse response) {
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.headers().get("grpc-status")).isEqualTo("0");
    }

    private static void checkResourceViaDiscoveryRequest(
            @Nullable ClusterLoadAssignment actualEndpoint, String resourceName) {
        await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(
                () -> checkResourceViaDiscoveryRequest0(actualEndpoint, resourceName));
    }

    private static void checkResourceViaDiscoveryRequest0(
            @Nullable ClusterLoadAssignment actualEndpoint, String resourceName)
            throws InterruptedException, InvalidProtocolBufferException {

        final EndpointDiscoveryServiceStub client = GrpcClients.newClient(
                dogma.httpClient().uri(), EndpointDiscoveryServiceStub.class);
        final BlockingQueue<DiscoveryResponse> queue = new ArrayBlockingQueue<>(2);
        final StreamObserver<DiscoveryRequest> requestStreamObserver = client.streamEndpoints(
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
                                                     .setTypeUrl(V3.ENDPOINT_TYPE_URL)
                                                     .addResourceNames(resourceName)
                                                     .build());
        if (actualEndpoint != null) {
            final DiscoveryResponse discoveryResponse = queue.take();
            final List<Any> resources = discoveryResponse.getResourcesList();
            assertThat(resources.size()).isOne();
            final Any any = resources.get(0);
            assertThat(any.getTypeUrl()).isEqualTo(V3.ENDPOINT_TYPE_URL);
            assertThat(actualEndpoint).isEqualTo(ClusterLoadAssignment.parseFrom(any.getValue()));
        } else {
            final DiscoveryResponse discoveryResponse = queue.poll(300, TimeUnit.MILLISECONDS);
            assertThat(discoveryResponse).isNull();
        }
        requestStreamObserver.onCompleted();
    }

    @Test
    void updateEndpointViaHttp() throws Exception {
        final ClusterLoadAssignment endpoint = loadAssignment("this_endpoint_name_will_be_ignored_and_replaced",
                                                              "127.0.0.1", 8080);
        AggregatedHttpResponse response = updateEndpoint("foo-endpoint/2", endpoint);
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        response = createEndpoint("groups/foo", "foo-endpoint/2", endpoint, dogma.httpClient());
        assertOk(response);
        final ClusterLoadAssignment.Builder endpointBuilder = ClusterLoadAssignment.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(response.contentUtf8(), endpointBuilder);
        final ClusterLoadAssignment actualEndpoint = endpointBuilder.build();
        final String clusterName = "groups/foo/clusters/foo-endpoint/2";
        assertThat(actualEndpoint).isEqualTo(endpoint.toBuilder().setClusterName(clusterName).build());
        checkResourceViaDiscoveryRequest(actualEndpoint, clusterName);

        final ClusterLoadAssignment updatingEndpoint =
                endpoint.toBuilder()
                        .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                         .addLbEndpoints(endpoint("127.0.0.1", 8081)))
                        .setClusterName(clusterName).build();
        response = updateEndpoint("foo-endpoint/2", updatingEndpoint);
        assertOk(response);
        final ClusterLoadAssignment.Builder endpointBuilder2 = ClusterLoadAssignment.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(response.contentUtf8(), endpointBuilder2);
        final ClusterLoadAssignment actualEndpoint2 = endpointBuilder2.build();
        assertThat(actualEndpoint2).isEqualTo(
                updatingEndpoint.toBuilder().setClusterName(clusterName).build());
        checkResourceViaDiscoveryRequest(actualEndpoint2, clusterName);
    }

    private static AggregatedHttpResponse updateEndpoint(
            String endpointId, ClusterLoadAssignment endpoint) throws IOException {
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.PATCH,
                                                              "/api/v1/xds/groups/foo/endpoints/" + endpointId)
                                                     .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                                                     .contentType(MediaType.JSON_UTF_8).build();
        return dogma.httpClient().execute(headers, JSON_MESSAGE_MARSHALLER.writeValueAsString(endpoint))
                    .aggregate().join();
    }

    @Test
    void deleteEndpointViaHttp() throws Exception {
        final String endpointName = "groups/foo/endpoints/foo-endpoint/3/4";
        final String clusterName = "groups/foo/clusters/foo-endpoint/3/4";
        AggregatedHttpResponse response = deleteEndpoint(endpointName);
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        final ClusterLoadAssignment endpoint = loadAssignment("this_endpoint_name_will_be_ignored_and_replaced",
                                                              "127.0.0.1", 8080);
        response = createEndpoint("groups/foo", "foo-endpoint/3/4", endpoint, dogma.httpClient());
        assertOk(response);

        final ClusterLoadAssignment actualEndpoint =
                endpoint.toBuilder().setClusterName(clusterName).build();
        checkResourceViaDiscoveryRequest(actualEndpoint, clusterName);

        // Add permission test.

        response = deleteEndpoint(endpointName);
        assertOk(response);
        assertThat(response.contentUtf8()).isEqualTo("{}");
        checkResourceViaDiscoveryRequest(null, clusterName);
    }

    private static AggregatedHttpResponse deleteEndpoint(String endpointName) {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.DELETE, "/api/v1/xds/" + endpointName)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .build();
        return dogma.httpClient().execute(headers).aggregate().join();
    }

    @Test
    void viaStub() throws Exception {
        final XdsEndpointServiceBlockingStub client =
                GrpcClients.builder(dogma.httpClient().uri())
                           .setHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                           .build(XdsEndpointServiceBlockingStub.class);
        final ClusterLoadAssignment endpoint = loadAssignment("this_endpoint_name_will_be_ignored_and_replaced",
                                                              "127.0.0.1", 8080);
        final ClusterLoadAssignment response = client.createEndpoint(
                CreateEndpointRequest.newBuilder()
                                     .setParent("groups/foo")
                                     .setEndpointId("foo-endpoint/5/6")
                                     .setEndpoint(endpoint)
                                     .build());
        final String clusterName = "groups/foo/clusters/foo-endpoint/5/6";
        assertThat(response).isEqualTo(endpoint.toBuilder().setClusterName(clusterName).build());
        checkResourceViaDiscoveryRequest(response, clusterName);

        final ClusterLoadAssignment updatingEndpoint =
                endpoint.toBuilder()
                        .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                         .addLbEndpoints(endpoint("127.0.0.1", 8081)))
                        .setClusterName(clusterName).build();

        final String endpointName = "groups/foo/endpoints/foo-endpoint/5/6";
        final ClusterLoadAssignment response2 = client.updateEndpoint(
                UpdateEndpointRequest.newBuilder()
                                     .setEndpointName(endpointName)
                                     .setEndpoint(updatingEndpoint)
                                     .build());
        assertThat(response2).isEqualTo(updatingEndpoint);
        checkResourceViaDiscoveryRequest(response2, clusterName);

        // No exception is thrown.
        final Empty ignored = client.deleteEndpoint(
                DeleteEndpointRequest.newBuilder()
                                     .setName(endpointName)
                                     .build());
        checkResourceViaDiscoveryRequest(null, clusterName);
    }
}
