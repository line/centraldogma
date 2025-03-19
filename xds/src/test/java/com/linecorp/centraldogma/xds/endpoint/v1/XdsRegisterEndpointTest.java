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
import static com.linecorp.centraldogma.xds.internal.XdsResourceManager.JSON_MESSAGE_MARSHALLER;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createEndpoint;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.createGroup;
import static com.linecorp.centraldogma.xds.internal.XdsTestUtil.endpoint;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

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

        // Register another endpoint to the same locality endpoint.
        final LocalityLbEndpoint localityLbEndpoint1 =
                LocalityLbEndpoint.newBuilder().setLocality(locality1)
                                  .setLbEndpoint(endpoint("127.0.0.1", 8081))
                                  .build();
        response = registerOrDeregister(endpointName, localityLbEndpoint1, true);
        assertOk(response);
        endpoint = endpoint.toBuilder()
                           .removeEndpoints(0)
                           .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                            .setLocality(locality1)
                                                            .addLbEndpoints(endpoint("127.0.0.1", 8080))
                                                            .addLbEndpoints(endpoint("127.0.0.1", 8081)))
                           .build();
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);

        // Register another endpoint with different priority.
        final LocalityLbEndpoint localityLbEndpoint2 =
                LocalityLbEndpoint.newBuilder().setLocality(locality1)
                                  .setPriority(1)
                                  .setLbEndpoint(endpoint("127.0.0.1", 8082))
                                  .build();
        response = registerOrDeregister(endpointName, localityLbEndpoint2, true);
        assertOk(response);
        endpoint = endpoint.toBuilder()
                           .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                            .setLocality(locality1)
                                                            .setPriority(1)
                                                            .addLbEndpoints(endpoint("127.0.0.1", 8082)))
                           .build();
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);

        // Register another endpoint with different locality.
        final Locality locality2 = Locality.newBuilder().setRegion("region2").setZone("zone2").build();
        LbEndpoint endpoint8083 = endpoint("127.0.0.1", 8083);
        final LocalityLbEndpoint localityLbEndpoint3 =
                LocalityLbEndpoint.newBuilder().setLocality(locality2)
                                  .setLbEndpoint(endpoint8083)
                                  .build();
        response = registerOrDeregister(endpointName, localityLbEndpoint3, true);
        assertOk(response);
        endpoint = endpoint.toBuilder()
                           .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                            .setLocality(locality2)
                                                            .addLbEndpoints(endpoint8083))
                           .build();
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);

        // Register the same endpoint with different property. This will replace the existing one.
        endpoint8083 = endpoint8083.toBuilder().setLoadBalancingWeight(UInt32Value.of(200)).build();
        final LocalityLbEndpoint localityLbEndpoint4 =
                LocalityLbEndpoint.newBuilder().setLocality(locality2)
                                  .setLbEndpoint(endpoint8083)
                                  .build();
        response = registerOrDeregister(endpointName, localityLbEndpoint4, true);
        assertOk(response);
        endpoint = endpoint.toBuilder()
                           .removeEndpoints(2)
                           .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                            .setLocality(locality2)
                                                            .addLbEndpoints(endpoint8083))
                           .build();
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);

        // Deregister the endpoint.
        response = registerOrDeregister(endpointName, localityLbEndpoint3, false);
        assertOk(response);
        assertThat(response.contentUtf8()).isEqualTo("{}");
        endpoint = endpoint.toBuilder()
                           .removeEndpoints(2)
                           .build();
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);

        // Try to deregister a non-existent endpoint.
        response = registerOrDeregister(endpointName, localityLbEndpoint3, false);
        assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);
        assertThat(response.contentUtf8()).contains("Locality LB endpoint does not exist");

        response = registerOrDeregister(endpointName, localityLbEndpoint2, false);
        assertOk(response);
        assertThat(response.contentUtf8()).isEqualTo("{}");
        endpoint = endpoint.toBuilder()
                           .removeEndpoints(1)
                           .build();
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);

        response = registerOrDeregister(endpointName,
                                        LocalityLbEndpoint.newBuilder().setLocality(locality1)
                                                      .setLbEndpoint(endpoint("127.0.0.1", 8080))
                                                      .build(),
                                        false);
        assertOk(response);
        assertThat(response.contentUtf8()).isEqualTo("{}");
        endpoint = endpoint.toBuilder()
                           .removeEndpoints(0)
                           .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                            .setLocality(locality1)
                                                            .addLbEndpoints(endpoint("127.0.0.1", 8081)))
                           .build();
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);

        response = registerOrDeregister(endpointName,
                                        LocalityLbEndpoint.newBuilder().setLocality(locality1)
                                                          .setLbEndpoint(endpoint("127.0.0.1", 8081))
                                                          .build(),
                                        false);
        assertOk(response);
        assertThat(response.contentUtf8()).isEqualTo("{}");
        endpoint = endpoint.toBuilder()
                           .removeEndpoints(0)
                           .build();
        checkEndpointsViaDiscoveryRequest(dogma.httpClient().uri(), endpoint, clusterName);
    }

    private static AggregatedHttpResponse registerOrDeregister(
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
                    .aggregate().join();
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
