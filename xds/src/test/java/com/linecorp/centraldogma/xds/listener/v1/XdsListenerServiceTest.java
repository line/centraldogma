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
package com.linecorp.centraldogma.xds.listener.v1;

import static com.linecorp.centraldogma.xds.internal.XdsServiceUtil.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createListener;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.exampleListener;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.updateListener;
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
import com.linecorp.centraldogma.xds.listener.v1.XdsListenerServiceGrpc.XdsListenerServiceBlockingStub;

import io.envoyproxy.controlplane.cache.Resources.V3;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.service.listener.v3.ListenerDiscoveryServiceGrpc.ListenerDiscoveryServiceStub;
import io.grpc.stub.StreamObserver;

class XdsListenerServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @BeforeAll
    static void setup() {
        final AggregatedHttpResponse response = createGroup("groups/foo", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.OK);
    }

    @Test
    void createListenerViaHttp() throws Exception {
        final Listener listener = exampleListener("this_listener_name_will_be_ignored_and_replaced",
                                                  "groups/foo/routes/foo-route", "stats");
        AggregatedHttpResponse response = createListener("groups/foo", "@invalid_listener_id",
                                                         listener, dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.BAD_REQUEST);

        response = createListener("groups/non-existent-group", "foo-listener/1", listener, dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        response = createListener("groups/foo", "foo-listener/1", listener, dogma.httpClient());
        assertOk(response);
        final Listener.Builder listenerBuilder = Listener.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(response.contentUtf8(), listenerBuilder);
        final Listener actualListener = listenerBuilder.build();
        final String listenerName = "groups/foo/listeners/foo-listener/1";
        assertThat(actualListener).isEqualTo(listener.toBuilder().setName(listenerName).build());
        await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(
                () -> checkResourceViaDiscoveryRequest(actualListener, listenerName, true));
    }

    private static void assertOk(AggregatedHttpResponse response) {
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.headers().get("grpc-status")).isEqualTo("0");
    }

    private static void checkResourceViaDiscoveryRequest(Listener actualListener, String resourceName,
                                                         boolean created)
            throws InterruptedException, InvalidProtocolBufferException {
        final ListenerDiscoveryServiceStub client = GrpcClients.newClient(
                dogma.httpClient().uri(), ListenerDiscoveryServiceStub.class);
        final BlockingQueue<DiscoveryResponse> queue = new ArrayBlockingQueue<>(2);
        final StreamObserver<DiscoveryRequest> requestStreamObserver = client.streamListeners(
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
                                                     .setTypeUrl(V3.LISTENER_TYPE_URL)
                                                     .addResourceNames(resourceName)
                                                     .build());
        if (created) {
            final DiscoveryResponse discoveryResponse = queue.take();
            final List<Any> resources = discoveryResponse.getResourcesList();
            assertThat(resources.size()).isOne();
            final Any any = resources.get(0);
            assertThat(any.getTypeUrl()).isEqualTo(V3.LISTENER_TYPE_URL);
            assertThat(actualListener).isEqualTo(Listener.parseFrom(any.getValue()));
        } else {
            final DiscoveryResponse discoveryResponse = queue.poll(300, TimeUnit.MILLISECONDS);
            assertThat(discoveryResponse).isNull();
        }
    }

    @Test
    void updateListenerViaHttp() throws Exception {
        final Listener listener = exampleListener("this_listener_name_will_be_ignored_and_replaced",
                                                  "groups/foo/routes/foo-route", "stats");
        AggregatedHttpResponse response = updateListener("groups/foo", "foo-listener/2", listener,
                                                         dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        response = createListener("groups/foo", "foo-listener/2", listener, dogma.httpClient());
        assertOk(response);
        final Listener.Builder listenerBuilder = Listener.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(response.contentUtf8(), listenerBuilder);
        final Listener actualListener = listenerBuilder.build();
        final String listenerName = "groups/foo/listeners/foo-listener/2";
        assertThat(actualListener).isEqualTo(listener.toBuilder().setName(listenerName).build());
        await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(
                () -> checkResourceViaDiscoveryRequest(actualListener, listenerName, true));

        final Listener updatingListener = listener.toBuilder()
                                                  .setStatPrefix("updated_stats")
                                                  .setName(listenerName).build();
        response = updateListener("groups/foo", "foo-listener/2", updatingListener, dogma.httpClient());
        assertOk(response);
        final Listener.Builder listenerBuilder2 = Listener.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(response.contentUtf8(), listenerBuilder2);
        final Listener actualListener2 = listenerBuilder2.build();
        assertThat(actualListener2).isEqualTo(updatingListener.toBuilder().setName(listenerName).build());
        await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(
                () -> checkResourceViaDiscoveryRequest(actualListener2, listenerName, true));
    }

    @Test
    void deleteListenerViaHttp() throws Exception {
        final String listenerName = "groups/foo/listeners/foo-listener/3/4";
        AggregatedHttpResponse response = deleteListener(listenerName);
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        final Listener listener = exampleListener("this_listener_name_will_be_ignored_and_replaced",
                                                  "groups/foo/routes/foo-route", "stats");
        response = createListener("groups/foo", "foo-listener/3/4", listener, dogma.httpClient());
        assertOk(response);

        final Listener actualListener = listener.toBuilder().setName(listenerName).build();
        await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(
                () -> checkResourceViaDiscoveryRequest(actualListener, listenerName, true));

        // Add permission test.

        response = deleteListener(listenerName);
        assertOk(response);
        assertThat(response.contentUtf8()).isEqualTo("{}");
        await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(
                () -> checkResourceViaDiscoveryRequest(actualListener, listenerName, false));
    }

    private static AggregatedHttpResponse deleteListener(String listenerName) {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.DELETE, "/api/v1/xds/" + listenerName)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .build();
        return dogma.httpClient().execute(headers).aggregate().join();
    }

    @Test
    void viaStub() {
        final XdsListenerServiceBlockingStub client =
                GrpcClients.builder(dogma.httpClient().uri())
                           .setHeader(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                           .build(XdsListenerServiceBlockingStub.class);
        final Listener listener = exampleListener("this_listener_name_will_be_ignored_and_replaced",
                                                  "groups/foo/routes/foo-route", "stats");
        Listener response = client.createListener(
                CreateListenerRequest.newBuilder()
                                     .setParent("groups/foo")
                                     .setListenerId("foo-listener/5/6")
                                     .setListener(listener)
                                     .build());
        final String listenerName = "groups/foo/listeners/foo-listener/5/6";
        assertThat(response).isEqualTo(listener.toBuilder().setName(listenerName).build());

        final Listener updatingListener = listener.toBuilder()
                                                  .setStatPrefix("updated_stats")
                                                  .setName(listenerName).build();
        response = client.updateListener(UpdateListenerRequest.newBuilder()
                                                              .setListener(updatingListener)
                                                              .build());
        assertThat(response).isEqualTo(updatingListener);

        // No exception is thrown.
        final Empty ignored = client.deleteListener(
                DeleteListenerRequest.newBuilder()
                                     .setName(listenerName)
                                     .build());
    }
}
