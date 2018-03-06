/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.centraldogma.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;

public class CentralDogmaBuilderTest {

    @Test
    public void mutuallyExclusiveHostAndProfile() {
        final CentralDogmaBuilder b1 = new CentralDogmaBuilder();
        b1.host("foo");
        assertThatThrownBy(() -> b1.profile("bar"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be used together");

        final CentralDogmaBuilder b2 = new CentralDogmaBuilder();
        b2.profile("foo");
        assertThatThrownBy(() -> b2.host("bar"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be used together");
    }

    @Test
    public void emptyProfile() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();

        assertThatThrownBy(() -> b.profile("bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no profile matches");
    }

    @Test
    public void mismatchingProfile() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();

        assertThatThrownBy(() -> b.profile("none"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no profile matches");
    }

    @Test
    public void buildingWithProfile() {
        final String groupName = "centraldogma-profile-foo";
        final String notGroupName = "centraldogma-profile-qux";
        try {
            final CentralDogmaBuilder b = new CentralDogmaBuilder();
            // The last valid profile should win, to be consistent with Spring Boot profiles.
            b.profile("qux", "foo");
            b.build();

            final EndpointGroup group = EndpointGroupRegistry.get(groupName);
            assertThat(group).isNotNull();
            assertThat(EndpointGroupRegistry.get(notGroupName)).isNull();

            final List<Endpoint> endpoints = group.endpoints();
            assertThat(endpoints).isNotNull();
            assertThat(endpoints).containsExactlyInAnyOrder(
                    Endpoint.of("foo.com", 36462),
                    Endpoint.of("bar.com", 8080));
        } finally {
            EndpointGroupRegistry.unregister(groupName);
        }
    }

    @Test
    public void buildingWithSingleHost() {
        final long id = CentralDogmaBuilder.nextAnonymousGroupId.get();
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        b.host("foo");
        b.build();

        // No new group should be registered.
        assertThat(CentralDogmaBuilder.nextAnonymousGroupId).hasValue(id);
        assertThat(EndpointGroupRegistry.get("centraldogma-anonymous-" + id)).isNull();
    }

    @Test
    public void buildingWithMultipleHosts() {
        final long id = CentralDogmaBuilder.nextAnonymousGroupId.get();
        final String groupName = "centraldogma-anonymous-" + id;
        try {
            final CentralDogmaBuilder b = new CentralDogmaBuilder();
            b.host("foo", 1);
            b.host("bar", 2);
            b.build();
            assertThat(CentralDogmaBuilder.nextAnonymousGroupId).hasValue(id + 1);

            final List<Endpoint> endpoints =
                    EndpointGroupRegistry.get(groupName).endpoints();
            assertThat(endpoints).isNotNull();
            assertThat(endpoints).containsExactlyInAnyOrder(
                    Endpoint.of("foo", 1), Endpoint.of("bar", 2));
        } finally {
            EndpointGroupRegistry.unregister(groupName);
        }
    }
}
