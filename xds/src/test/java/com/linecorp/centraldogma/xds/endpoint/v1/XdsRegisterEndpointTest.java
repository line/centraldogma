/*
 * Copyright 2025 LINE Corporation
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

import static com.linecorp.centraldogma.xds.endpoint.v1.XdsEndpointServiceTest.assertOk;
import static com.linecorp.centraldogma.xds.endpoint.v1.XdsEndpointServiceTest.checkEndpointsViaDiscoveryRequest;
import static com.linecorp.centraldogma.xds.internal.ControlPlanePlugin.XDS_CENTRAL_DOGMA_PROJECT;
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createEndpoint;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.endpoint;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.UInt32Value;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

public class XdsRegisterEndpointTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension();

    @BeforeAll
    static void setup() {
        final AggregatedHttpResponse response = createGroup("foo", dogma.httpClient());
        assertThat(response.status()).isSameAs(HttpStatus.OK);
    }

    @Test
    void registerOrDeregister() throws Exception {
        final String clusterName = "groups/foo/clusters/foo-endpoint/1";
        final String endpointName = "groups/foo/endpoints/foo-endpoint/1";
        final Locality locality1 = Locality.newBuilder().setRegion("region1").setZone("zone1").build();
        ClusterLoadAssignment endpoint = loadAssignment(clusterName, locality1,
                                                        endpoint("127.0.0.1", 8080));
        AggregatedHttpResponse response = createEndpoint("groups/foo", "foo-endpoint/1",
                                                         endpoint, dogma.httpClient());
        assertOk(response);
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);

        final Repository fooRepository =
                dogma.projectManager().get(XDS_CENTRAL_DOGMA_PROJECT).repos().get("foo");
        int prevMajor = fooRepository.normalizeNow(Revision.HEAD).major();

        // Register endpoints to the same locality endpoint.
        final LocalityLbEndpoint localityLbEndpoint1 =
                LocalityLbEndpoint.newBuilder().setLocality(locality1)
                                  .setLbEndpoint(endpoint("127.0.0.1", 8081))
                                  .build();
        final LocalityLbEndpoint localityLbEndpoint2 =
                LocalityLbEndpoint.newBuilder().setLocality(locality1)
                                  .setLbEndpoint(endpoint("127.0.0.1", 8082))
                                  .build();
        final CompletableFuture<AggregatedHttpResponse> registerFuture1 =
                registerOrDeregisterAsync(endpointName, localityLbEndpoint1, true);
        // The service collects the request during 3 seconds.
        Thread.sleep(1000);
        final CompletableFuture<AggregatedHttpResponse> registerFuture2 =
                registerOrDeregisterAsync(endpointName, localityLbEndpoint2, true);
        final CompletableFuture<AggregatedHttpResponse> deregister =
                registerOrDeregisterAsync(endpointName,
                                          LocalityLbEndpoint.newBuilder().setLocality(locality1)
                                                            .setLbEndpoint(endpoint("127.0.0.1", 8080))
                                                            .build(), false);
        assertOk(registerFuture1.join());
        assertOk(registerFuture2.join());
        assertOk(deregister.join());
        // localityLbEndpoint1 and localityLbEndpoint2 are registered together so the major version should
        // be incremented by 1.
        assertThat(fooRepository.normalizeNow(Revision.HEAD).major()).isEqualTo(prevMajor + 1);

        endpoint = endpoint.toBuilder()
                           .removeEndpoints(0)
                           .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                            .setLocality(locality1)
                                                            .addLbEndpoints(endpoint("127.0.0.1", 8081))
                                                            .addLbEndpoints(endpoint("127.0.0.1", 8082)))
                           .build();
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);

        prevMajor = fooRepository.normalizeNow(Revision.HEAD).major();

        // Register another endpoint with different priority.
        final LocalityLbEndpoint localityLbEndpoint3 =
                LocalityLbEndpoint.newBuilder().setLocality(locality1)
                                  .setPriority(1)
                                  .setLbEndpoint(endpoint("127.0.0.1", 8083))
                                  .build();

        final CompletableFuture<AggregatedHttpResponse> registerFuture3 =
                registerOrDeregisterAsync(endpointName, localityLbEndpoint3, true);

        final LocalityLbEndpoint notRegistered =
                LocalityLbEndpoint.newBuilder().setLocality(locality1)
                                  .setPriority(1)
                                  .setLbEndpoint(endpoint("127.0.0.1", 9000))
                                  .build();

        // Try to register and deregister the same endpoint.
        final CompletableFuture<AggregatedHttpResponse> notRegisterFuture1 =
                registerOrDeregisterAsync(endpointName, notRegistered, true);
        // Sleep so that the next call is sent after the previous one.
        Thread.sleep(500);
        final CompletableFuture<AggregatedHttpResponse> notRegisterFuture2 =
                registerOrDeregisterAsync(endpointName, notRegistered, false);

        assertOk(registerFuture3.join());
        // Aborted by the next call.
        assertThat(notRegisterFuture1.join().status()).isSameAs(HttpStatus.CONFLICT);
        assertThat(notRegisterFuture2.join().status()).isSameAs(HttpStatus.OK);

        assertThat(fooRepository.normalizeNow(Revision.HEAD).major()).isEqualTo(prevMajor + 1);

        endpoint = endpoint.toBuilder()
                           .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                            .setLocality(locality1)
                                                            .setPriority(1)
                                                            .addLbEndpoints(endpoint("127.0.0.1", 8083)))
                           .build();
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);

        // Register another endpoint with different locality.
        final Locality locality2 = Locality.newBuilder().setRegion("region2").setZone("zone2").build();
        LbEndpoint endpoint8084 = endpoint("127.0.0.1", 8084);
        final LocalityLbEndpoint localityLbEndpoint4 =
                LocalityLbEndpoint.newBuilder().setLocality(locality2)
                                  .setLbEndpoint(endpoint8084)
                                  .build();
        response = registerOrDeregister(endpointName, localityLbEndpoint4, true);
        assertOk(response);
        endpoint = endpoint.toBuilder()
                           .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                            .setLocality(locality2)
                                                            .addLbEndpoints(endpoint8084))
                           .build();
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);

        // Register the same endpoint with different property. This will replace the existing one.
        endpoint8084 = endpoint8084.toBuilder().setLoadBalancingWeight(UInt32Value.of(200)).build();
        final LocalityLbEndpoint localityLbEndpoint5 =
                LocalityLbEndpoint.newBuilder().setLocality(locality2)
                                  .setLbEndpoint(endpoint8084)
                                  .build();
        response = registerOrDeregister(endpointName, localityLbEndpoint5, true);
        assertOk(response);
        endpoint = endpoint.toBuilder()
                           .removeEndpoints(2)
                           .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                            .setLocality(locality2)
                                                            .addLbEndpoints(endpoint8084))
                           .build();
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);

        // Deregister the endpoint.
        response = registerOrDeregister(endpointName, localityLbEndpoint4, false);
        assertOk(response);
        assertThat(response.contentUtf8()).isEqualTo("{}");
        endpoint = endpoint.toBuilder()
                           .removeEndpoints(2)
                           .build();
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);

        // Try to deregister a non-existent endpoint.
        response = registerOrDeregister(endpointName, localityLbEndpoint4, false);
        assertThat(response.status()).isSameAs(HttpStatus.OK); // It's OK to deregister a non-existent endpoint.

        response = registerOrDeregister(endpointName, localityLbEndpoint3, false);
        assertOk(response);
        assertThat(response.contentUtf8()).isEqualTo("{}");
        endpoint = endpoint.toBuilder()
                           .removeEndpoints(1)
                           .build();
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);

        prevMajor = fooRepository.normalizeNow(Revision.HEAD).major();
        final CompletableFuture<AggregatedHttpResponse> deregisterFuture1 =
                registerOrDeregisterAsync(endpointName,
                                          LocalityLbEndpoint.newBuilder().setLocality(locality1)
                                                            .setLbEndpoint(endpoint("127.0.0.1", 8081))
                                                            .build(),
                                          false);
        Thread.sleep(1000);
        final CompletableFuture<AggregatedHttpResponse> deregisterFuture2 =
                registerOrDeregisterAsync(endpointName,
                                          LocalityLbEndpoint.newBuilder().setLocality(locality1)
                                                            .setLbEndpoint(endpoint("127.0.0.1", 8082))
                                                            .build(),
                                          false);
        assertOk(deregisterFuture1.join());
        assertOk(deregisterFuture2.join());

        assertThat(fooRepository.normalizeNow(Revision.HEAD).major()).isEqualTo(prevMajor + 1);

        assertThat(deregisterFuture1.join().contentUtf8()).isEqualTo("{}");
        assertThat(deregisterFuture2.join().contentUtf8()).isEqualTo("{}");
        endpoint = endpoint.toBuilder()
                           .removeEndpoints(0)
                           .build();
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);
    }

    private static AggregatedHttpResponse registerOrDeregister(
            String endpointName, LocalityLbEndpoint localityLbEndpoint, boolean register) throws IOException {
        return registerOrDeregisterAsync(endpointName, localityLbEndpoint, register).join();
    }

    private static CompletableFuture<AggregatedHttpResponse> registerOrDeregisterAsync(
            String endpointName, LocalityLbEndpoint localityLbEndpoint, boolean register) throws IOException {
        final RequestHeaders headers =
                RequestHeaders.builder(register ? HttpMethod.PATCH : HttpMethod.DELETE,
                                       "/api/v1/xds/" + endpointName +
                                       (register ? ":registerLocalityLbEndpoint"
                                                 : ":deregisterLocalityLbEndpoint"))
                              .set(HttpHeaderNames.AUTHORIZATION, "Bearer anonymous")
                              .contentType(MediaType.JSON_UTF_8).build();
        return dogma.httpClient().execute(headers,
                                          JSON_MESSAGE_MARSHALLER.writeValueAsString(localityLbEndpoint))
                    .aggregate();
    }

    private static ClusterLoadAssignment loadAssignment(String clusterName, Locality locality,
                                                        LbEndpoint endpoint) {
        return ClusterLoadAssignment.newBuilder()
                                    .setClusterName(clusterName)
                                    .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                                     .setLocality(locality)
                                                                     .addLbEndpoints(endpoint))
                                    .build();
    }

    @Test
    void invalidRegister() throws IOException {
        final String endpointName = "groups/foo/endpoints/non-existent/1";
        final Locality locality1 = Locality.newBuilder().setRegion("region1").setZone("zone1").build();
        final LocalityLbEndpoint localityLbEndpoint =
                LocalityLbEndpoint.newBuilder().setLocality(locality1)
                                  .setLbEndpoint(endpoint("127.0.0.1", 8081))
                                  .build();
        AggregatedHttpResponse response = registerOrDeregister(endpointName, localityLbEndpoint, true);
        // endpoint name is not found.
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);

        // same as above but deregister
        response = registerOrDeregister(endpointName, localityLbEndpoint, false);
        // endpoint name is not found.
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);
    }
}
