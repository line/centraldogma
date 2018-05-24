/*
 * Copyright 2018 LINE Corporation
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

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup;

public class ArmeriaCentralDogmaBuilderTest {

    // Note: This test case relies on http://xip.io/

    @Test
    public void buildingWithProfile() throws Exception {
        final String groupName = "centraldogma-profile-test-xip";
        try {
            final ArmeriaCentralDogmaBuilder b = new ArmeriaCentralDogmaBuilder();
            b.disableHealthCheck();
            b.profile("test-xip");
            final Endpoint endpoint = b.endpoint();
            assertThat(endpoint.isGroup()).isTrue();
            assertThat(endpoint.groupName()).isEqualTo(groupName);

            final EndpointGroup group = EndpointGroupRegistry.get(groupName);

            assertThat(group).isInstanceOf(CompositeEndpointGroup.class);
            final CompositeEndpointGroup compositeGroup = (CompositeEndpointGroup) group;
            final List<EndpointGroup> childGroups = compositeGroup.groups();
            assertThat(childGroups).hasSize(2);
            assertThat(childGroups.get(0)).isInstanceOf(DnsAddressEndpointGroup.class);
            assertThat(childGroups.get(1)).isInstanceOf(DnsAddressEndpointGroup.class);

            // Wait until all DNS queries are done.
            for (EndpointGroup g : childGroups) {
                ((DynamicEndpointGroup) g).awaitInitialEndpoints(10, TimeUnit.SECONDS);
            }

            final List<Endpoint> endpoints = group.endpoints();
            assertThat(endpoints).isNotNull();
            assertThat(endpoints).containsExactlyInAnyOrder(
                    Endpoint.of("1.2.3.4.xip.io", 36462).withIpAddr("1.2.3.4"),
                    Endpoint.of("5.6.7.8.xip.io", 8080).withIpAddr("5.6.7.8"));
        } finally {
            EndpointGroupRegistry.unregister(groupName);
        }
    }

    @Test
    public void buildingWithSingleResolvedHost() throws Exception {
        final long id = AbstractArmeriaCentralDogmaBuilder.nextAnonymousGroupId.get();
        final ArmeriaCentralDogmaBuilder b = new ArmeriaCentralDogmaBuilder();
        b.host("1.2.3.4");
        assertThat(b.endpoint()).isEqualTo(Endpoint.of("1.2.3.4", 36462));

        // No new group should be registered.
        assertThat(AbstractArmeriaCentralDogmaBuilder.nextAnonymousGroupId).hasValue(id);
        assertThat(EndpointGroupRegistry.get("centraldogma-anonymous-" + id)).isNull();
    }

    @Test
    public void buildingWithSingleUnresolvedHost() throws Exception {
        final long id = AbstractArmeriaCentralDogmaBuilder.nextAnonymousGroupId.get();
        final String expectedGroupName = "centraldogma-anonymous-" + id;

        final ArmeriaCentralDogmaBuilder b = new ArmeriaCentralDogmaBuilder();
        b.disableHealthCheck();
        b.host("1.2.3.4.xip.io");
        assertThat(b.endpoint()).isEqualTo(Endpoint.ofGroup(expectedGroupName));

        // A new group should be registered.
        assertThat(AbstractArmeriaCentralDogmaBuilder.nextAnonymousGroupId).hasValue(id + 1);
        final EndpointGroup group = EndpointGroupRegistry.get(expectedGroupName);
        assertThat(group).isInstanceOf(DnsAddressEndpointGroup.class);
        assertThat(group.endpoints()).containsExactly(
                Endpoint.of("1.2.3.4.xip.io", 36462).withIpAddr("1.2.3.4"));
    }

    @Test
    public void buildingWithMultipleHosts() throws Exception {
        final long id = AbstractArmeriaCentralDogmaBuilder.nextAnonymousGroupId.get();
        final String groupName = "centraldogma-anonymous-" + id;
        try {
            final ArmeriaCentralDogmaBuilder b = new ArmeriaCentralDogmaBuilder();
            b.disableHealthCheck();
            b.host("1.2.3.4.xip.io", 1); // Unresolved host
            b.host("5.6.7.8.xip.io", 2); // Another unresolved host
            b.host("4.3.2.1", 3); // Resolved host
            b.host("8.7.6.5", 4); // Another resolved host

            final Endpoint endpoint = b.endpoint();
            assertThat(endpoint.isGroup()).isTrue();
            assertThat(endpoint.groupName()).isEqualTo(groupName);

            assertThat(AbstractArmeriaCentralDogmaBuilder.nextAnonymousGroupId).hasValue(id + 1);

            final EndpointGroup group = EndpointGroupRegistry.get(groupName);

            assertThat(group).isInstanceOf(CompositeEndpointGroup.class);
            final CompositeEndpointGroup compositeGroup = (CompositeEndpointGroup) group;
            final List<EndpointGroup> childGroups = compositeGroup.groups();
            assertThat(childGroups).hasSize(3);
            assertThat(childGroups.get(0)).isInstanceOf(DnsAddressEndpointGroup.class);
            assertThat(childGroups.get(1)).isInstanceOf(DnsAddressEndpointGroup.class);
            assertThat(childGroups.get(2)).isInstanceOf(StaticEndpointGroup.class);

            // Wait until all DNS queries are done.
            for (EndpointGroup g : childGroups) {
                if (g instanceof DynamicEndpointGroup) {
                    ((DynamicEndpointGroup) g).awaitInitialEndpoints(10, TimeUnit.SECONDS);
                }
            }

            final List<Endpoint> endpoints = group.endpoints();
            assertThat(endpoints).isNotNull();
            assertThat(endpoints).containsExactlyInAnyOrder(
                    Endpoint.of("1.2.3.4.xip.io", 1).withIpAddr("1.2.3.4"),
                    Endpoint.of("5.6.7.8.xip.io", 2).withIpAddr("5.6.7.8"),
                    Endpoint.of("4.3.2.1", 3),
                    Endpoint.of("8.7.6.5", 4));
        } finally {
            EndpointGroupRegistry.unregister(groupName);
        }
    }

    private static final class ArmeriaCentralDogmaBuilder
            extends AbstractArmeriaCentralDogmaBuilder<ArmeriaCentralDogmaBuilder> {}
}
