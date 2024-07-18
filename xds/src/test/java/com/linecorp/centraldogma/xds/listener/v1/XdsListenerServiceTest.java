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

import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.exampleListener;
import static com.linecorp.centraldogma.xds.listener.v1.XdsListenerService.JSON_MESSAGE_MARSHALLER;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.Empty;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;
import com.linecorp.centraldogma.xds.listener.v1.XdsListenerServiceGrpc.XdsListenerServiceBlockingStub;

import io.envoyproxy.envoy.config.listener.v3.Listener;

class XdsListenerServiceTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @BeforeAll
    static void setup() {
        final AggregatedHttpResponse response = createGroup("groups/foo");
        assertThat(response.status()).isSameAs(HttpStatus.OK);
    }

    private static AggregatedHttpResponse createGroup(String groupName) {
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.POST, "/api/v1/xds/groups")
                                                     .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                                                     .contentType(MediaType.JSON_UTF_8).build();
        return dogma.httpClient().execute(headers, "{\"group\": {\"name\":\"" + groupName + "\"}}")
                    .aggregate().join();
    }

    @Test
    void createListenerViaHttp() throws IOException {
        final Listener listener = exampleListener("this_listener_name_will_be_ignored_and_replaced",
                                                  "groups/foo/routes/foo-route", "stats");
        AggregatedHttpResponse response = createListener("foo", "@invalid_listener_id", listener);
        assertThat(response.status()).isSameAs(HttpStatus.BAD_REQUEST);

        response = createListener("non-existent-group", "foo-listener", listener);
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        response = createListener("foo", "foo-listener", listener);
        assertOk(response);
        final Listener.Builder listenerBuilder = Listener.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(response.contentUtf8(), listenerBuilder);
        assertThat(listenerBuilder.build()).isEqualTo(
                listener.toBuilder().setName("groups/foo/listeners/foo-listener").build());
    }

    private static AggregatedHttpResponse createListener(
            String groupName, String listenerId, Listener listener) throws IOException {
        final RequestHeaders headers =
                RequestHeaders.builder(HttpMethod.POST,
                                       "/api/v1/xds/groups/" + groupName + "/listeners?listener_id="
                                       + listenerId)
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .contentType(MediaType.JSON_UTF_8).build();
        return dogma.httpClient().execute(headers, JSON_MESSAGE_MARSHALLER.writeValueAsString(listener))
                    .aggregate().join();
    }

    private static void assertOk(AggregatedHttpResponse response) {
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.headers().get("grpc-status")).isEqualTo("0");
    }

    @Test
    void updateListenerViaHttp() throws IOException {
        final Listener listener = exampleListener("this_listener_name_will_be_ignored_and_replaced",
                                                  "groups/foo/routes/foo-route", "stats");
        AggregatedHttpResponse response = updateListener("bar-listener", listener);
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        response = createListener("foo", "bar-listener", listener);
        assertOk(response);
        final Listener.Builder listenerBuilder = Listener.newBuilder();
        JSON_MESSAGE_MARSHALLER.mergeValue(response.contentUtf8(), listenerBuilder);
        assertThat(listenerBuilder.build()).isEqualTo(
                listener.toBuilder().setName("groups/foo/listeners/bar-listener").build());
    }

    private static AggregatedHttpResponse updateListener(
            String listenerId, Listener listener) throws IOException {
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.PATCH,
                                                              "/api/v1/xds/groups/foo/listeners/" + listenerId)
                                                     .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                                                     .contentType(MediaType.JSON_UTF_8).build();
        return dogma.httpClient().execute(headers, JSON_MESSAGE_MARSHALLER.writeValueAsString(listener))
                    .aggregate().join();
    }

    @Test
    void deleteListenerViaHttp() throws IOException {
        AggregatedHttpResponse response = deleteListener("groups/foo/listeners/baz-listener");
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        final Listener listener = exampleListener("this_listener_name_will_be_ignored_and_replaced",
                                                  "groups/foo/routes/foo-route", "stats");
        response = createListener("foo", "baz-listener", listener);
        assertOk(response);

        // Add permission test.

        response = deleteListener("groups/foo/listeners/baz-listener");
        assertOk(response);
        assertThat(response.contentUtf8()).isEqualTo("{}");
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
                                     .setListenerId("stub-listener")
                                     .setListener(listener)
                                     .build());
        final String listenerName = "groups/foo/listeners/stub-listener";
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
