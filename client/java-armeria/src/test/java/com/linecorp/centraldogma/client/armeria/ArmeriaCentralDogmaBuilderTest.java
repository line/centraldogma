/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.centraldogma.client.armeria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup;

class ArmeriaCentralDogmaBuilderTest {

    // Note: This test case relies on https://sslip.io/

    @Test
    void buildingWithProfile() throws Exception {
        final ArmeriaCentralDogmaBuilder b = new ArmeriaCentralDogmaBuilder();
        b.healthCheckIntervalMillis(0);
        b.profile("sslip");
        final EndpointGroup group = b.endpointGroup();

        assertThat(group).isNotNull();
        assertThat(group).isInstanceOf(CompositeEndpointGroup.class);
        final CompositeEndpointGroup compositeGroup = (CompositeEndpointGroup) group;
        final List<EndpointGroup> childGroups = compositeGroup.groups();
        assertThat(childGroups).hasSize(2);
        assertThat(childGroups.get(0)).isInstanceOf(DnsAddressEndpointGroup.class);
        assertThat(childGroups.get(1)).isInstanceOf(DnsAddressEndpointGroup.class);

        await().untilAsserted(() -> {
            final List<Endpoint> endpoints = group.endpoints();
            assertThat(endpoints).isNotNull();
            assertThat(endpoints).containsExactlyInAnyOrder(
                    Endpoint.of("1.2.3.4.sslip.io", 36462).withIpAddr("1.2.3.4"),
                    Endpoint.of("5.6.7.8.sslip.io", 8080).withIpAddr("5.6.7.8"));
        });
    }

    @Test
    void buildingWithSingleResolvedHost() throws Exception {
        final ArmeriaCentralDogmaBuilder b = new ArmeriaCentralDogmaBuilder();
        b.host("1.2.3.4");
        assertThat(b.endpointGroup()).isEqualTo(Endpoint.of("1.2.3.4", 36462));
    }

    @Test
    void buildingWithSingleUnresolvedHost() throws Exception {
        final ArmeriaCentralDogmaBuilder b = new ArmeriaCentralDogmaBuilder();
        b.healthCheckIntervalMillis(0);
        b.host("1.2.3.4.sslip.io");
        assertThat(b.endpointGroup()).isInstanceOf(DnsAddressEndpointGroup.class);
        assertThat(b.endpointGroup().endpoints().size()).isEqualTo(1);
        assertThat(b.endpointGroup().endpoints().get(0).host()).isEqualTo("1.2.3.4.sslip.io");
    }

    @Test
    void buildingWithMultipleHosts() throws Exception {
        final ArmeriaCentralDogmaBuilder b = new ArmeriaCentralDogmaBuilder();
        b.healthCheckIntervalMillis(0);
        b.host("1.2.3.4.sslip.io", 1); // Unresolved host
        b.host("5.6.7.8.sslip.io", 2); // Another unresolved host
        b.host("4.3.2.1", 3); // Resolved host
        b.host("8.7.6.5", 4); // Another resolved host

        final EndpointGroup endpointGroup = b.endpointGroup();
        assertThat(endpointGroup).isNotNull();
        assertThat(endpointGroup).isInstanceOf(CompositeEndpointGroup.class);
        final CompositeEndpointGroup compositeGroup = (CompositeEndpointGroup) endpointGroup;
        final List<EndpointGroup> childGroups = compositeGroup.groups();
        assertThat(childGroups).hasSize(3);
        assertThat(childGroups.get(0)).isInstanceOf(DnsAddressEndpointGroup.class);
        assertThat(childGroups.get(1)).isInstanceOf(DnsAddressEndpointGroup.class);
        assertThat(childGroups.get(2).toString()).contains("StaticEndpointGroup");

        await().untilAsserted(() -> {
            final List<Endpoint> endpoints = endpointGroup.endpoints();
            assertThat(endpoints).isNotNull();
            assertThat(endpoints).containsExactlyInAnyOrder(
                    Endpoint.of("1.2.3.4.sslip.io", 1).withIpAddr("1.2.3.4"),
                    Endpoint.of("5.6.7.8.sslip.io", 2).withIpAddr("5.6.7.8"),
                    Endpoint.of("4.3.2.1", 3),
                    Endpoint.of("8.7.6.5", 4));
        });
    }

    /**
     * Ensure the customization order of {@link ClientBuilder}.
     * <ol>
     *   <li>Builder-specified {@link Consumer}</li>
     *   <li>User-specified {@link ArmeriaClientConfigurator}</li>
     *   <li>User-specified {@link ClientFactory}</li>
     * </ol>
     */
    @Test
    void newClientBuilderCustomizationOrder() {
        final ClientFactory cf1 = mock(ClientFactory.class);
        final ClientFactory cf2 = mock(ClientFactory.class);
        final ClientFactory cf3 = mock(ClientFactory.class);
        final ArmeriaCentralDogmaBuilder b = new ArmeriaCentralDogmaBuilder();
        final StringBuilder buf = new StringBuilder();
        b.clientFactory(cf1);
        b.clientConfigurator(cb -> {
            buf.append('2');
            cb.factory(cf2);
        });

        final ClientBuilder cb = b.newClientBuilder("none+http", Endpoint.of("127.0.0.1"),
                                                    cb2 -> {
                                                        cb2.factory(cf3);
                                                        buf.append('1');
                                                    }, "/");
        assertThat(buf.toString()).isEqualTo("12");

        cb.build(HttpClient.class);
        verify(cf1, times(1)).newClient(any());
        verify(cf2, never()).newClient(any());
        verify(cf3, never()).newClient(any());
    }

    private static final class ArmeriaCentralDogmaBuilder
            extends AbstractArmeriaCentralDogmaBuilder<ArmeriaCentralDogmaBuilder> {}
}
