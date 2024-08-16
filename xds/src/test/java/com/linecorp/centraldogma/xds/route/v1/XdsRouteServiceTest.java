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
package com.linecorp.centraldogma.xds.route.v1;

import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createRoute;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.routeConfiguration;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.updateRoute;
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
import com.google.protobuf.Empty;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;
import com.linecorp.centraldogma.xds.route.v1.XdsRouteServiceGrpc.XdsRouteServiceBlockingStub;

import io.envoyproxy.controlplane.cache.Resources.V3;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.service.route.v3.RouteDiscoveryServiceGrpc.RouteDiscoveryServiceStub;
import io.grpc.stub.StreamObserver;

class XdsRouteServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @BeforeAll
    static void setup() {
        final AggregatedHttpResponse response = createGroup("foo", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.OK);
    }

    @Test
    void createRouteViaHttp() throws Exception {
        final RouteConfiguration route = routeConfiguration("this_route_name_will_be_ignored_and_replaced",
                                                            "groups/foo/clusters/foo-cluster");
        AggregatedHttpResponse response = createRoute("groups/foo", "@invalid_route_id",
                                                      route, dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.BAD_REQUEST);

        response = createRoute("groups/non-existent-group", "foo-route/1", route, dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        response = createRoute("groups/foo", "foo-route/1", route, dogma.httpClient());
        assertOk(response);
        final RouteConfiguration.Builder routeBuilder = RouteConfiguration.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(response.contentUtf8(), routeBuilder);
        final RouteConfiguration actualRoute = routeBuilder.build();
        final String routeName = "groups/foo/routes/foo-route/1";
        assertThat(actualRoute).isEqualTo(route.toBuilder().setName(routeName).build());
        checkResourceViaDiscoveryRequest(actualRoute, routeName, true);
    }

    private static void assertOk(AggregatedHttpResponse response) {
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.headers().get("grpc-status")).isEqualTo("0");
    }

    private static void checkResourceViaDiscoveryRequest(RouteConfiguration actualRoute, String resourceName,
                                                         boolean created) {
        await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(
                () -> checkResourceViaDiscoveryRequest0(actualRoute, resourceName, created));
    }

    private static void checkResourceViaDiscoveryRequest0(RouteConfiguration actualRoute, String resourceName,
                                                          boolean created)
            throws InterruptedException, InvalidProtocolBufferException {
        final RouteDiscoveryServiceStub client = GrpcClients.newClient(
                dogma.httpClient().uri(), RouteDiscoveryServiceStub.class);
        final BlockingQueue<DiscoveryResponse> queue = new ArrayBlockingQueue<>(2);
        final StreamObserver<DiscoveryRequest> requestStreamObserver = client.streamRoutes(
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
                                                     .setTypeUrl(V3.ROUTE_TYPE_URL)
                                                     .addResourceNames(resourceName)
                                                     .build());
        if (created) {
            final DiscoveryResponse discoveryResponse = queue.take();
            final List<Any> resources = discoveryResponse.getResourcesList();
            assertThat(resources.size()).isOne();
            final Any any = resources.get(0);
            assertThat(any.getTypeUrl()).isEqualTo(V3.ROUTE_TYPE_URL);
            assertThat(actualRoute).isEqualTo(RouteConfiguration.parseFrom(any.getValue()));
        } else {
            final DiscoveryResponse discoveryResponse = queue.poll(300, TimeUnit.MILLISECONDS);
            assertThat(discoveryResponse).isNull();
        }
    }

    @Test
    void updateRouteViaHttp() throws Exception {
        final RouteConfiguration route = routeConfiguration("this_route_name_will_be_ignored_and_replaced",
                                                            "groups/foo/clusters/foo-cluster");
        AggregatedHttpResponse response = updateRoute("groups/foo", "foo-route/2", route, dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        response = createRoute("groups/foo", "foo-route/2", route, dogma.httpClient());
        assertOk(response);
        final RouteConfiguration.Builder routeBuilder = RouteConfiguration.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(response.contentUtf8(), routeBuilder);
        final RouteConfiguration actualRoute = routeBuilder.build();
        final String routeName = "groups/foo/routes/foo-route/2";
        assertThat(actualRoute).isEqualTo(route.toBuilder().setName(routeName).build());
        checkResourceViaDiscoveryRequest(actualRoute, routeName, true);

        final RouteConfiguration updatingRoute = route.toBuilder()
                                                      .addInternalOnlyHeaders("internal")
                                                      .setName(routeName).build();
        response = updateRoute("groups/foo", "foo-route/2", updatingRoute, dogma.httpClient());
        assertOk(response);
        final RouteConfiguration.Builder routeBuilder2 = RouteConfiguration.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(response.contentUtf8(), routeBuilder2);
        final RouteConfiguration actualRoute2 = routeBuilder2.build();
        assertThat(actualRoute2).isEqualTo(updatingRoute.toBuilder().setName(routeName).build());
        checkResourceViaDiscoveryRequest(actualRoute2, routeName, true);
    }

    @Test
    void deleteRouteViaHttp() throws Exception {
        final String routeName = "groups/foo/routes/foo-route/3/4";
        AggregatedHttpResponse response = deleteRoute(routeName);
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        final RouteConfiguration route = routeConfiguration("this_route_name_will_be_ignored_and_replaced",
                                                            "groups/foo/clusters/foo-cluster");
        response = createRoute("groups/foo", "foo-route/3/4", route, dogma.httpClient());
        assertOk(response);

        final RouteConfiguration actualRoute = route.toBuilder().setName(routeName).build();
        checkResourceViaDiscoveryRequest(actualRoute, routeName, true);

        // Add permission test.

        response = deleteRoute(routeName);
        assertOk(response);
        assertThat(response.contentUtf8()).isEqualTo("{}");
        checkResourceViaDiscoveryRequest(actualRoute, routeName, false);
    }

    private static AggregatedHttpResponse deleteRoute(String routeName) {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.DELETE, "/api/v1/xds/" + routeName)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .build();
        return dogma.httpClient().execute(headers).aggregate().join();
    }

    @Test
    void viaStub() {
        final XdsRouteServiceBlockingStub client =
                GrpcClients.builder(dogma.httpClient().uri())
                           .setHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                           .build(XdsRouteServiceBlockingStub.class);
        final RouteConfiguration route = routeConfiguration("this_route_name_will_be_ignored_and_replaced",
                                                            "groups/foo/clusters/foo-cluster");
        RouteConfiguration response = client.createRoute(
                CreateRouteRequest.newBuilder()
                                  .setParent("groups/foo")
                                  .setRouteId("foo-route/5/6")
                                  .setRoute(route)
                                  .build());
        final String routeName = "groups/foo/routes/foo-route/5/6";
        assertThat(response).isEqualTo(route.toBuilder().setName(routeName).build());

        final RouteConfiguration updatingRoute = route.toBuilder()
                                                      .addInternalOnlyHeaders("internal")
                                                      .setName(routeName).build();
        response = client.updateRoute(UpdateRouteRequest.newBuilder()
                                                        .setRoute(updatingRoute)
                                                        .build());
        assertThat(response).isEqualTo(updatingRoute);

        // No exception is thrown.
        final Empty ignored = client.deleteRoute(
                DeleteRouteRequest.newBuilder()
                                  .setName(routeName)
                                  .build());
    }
}
