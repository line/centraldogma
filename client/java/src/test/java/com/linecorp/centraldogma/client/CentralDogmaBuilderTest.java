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

import java.net.InetSocketAddress;

import org.junit.Test;

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
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        // The last valid profile should win, to be consistent with Spring Boot profiles.
        b.profile("qux", "foo");
        assertThat(b.hosts()).containsExactlyInAnyOrder(
                InetSocketAddress.createUnresolved("foo.com", 36462),
                InetSocketAddress.createUnresolved("bar.com", 8080));
    }

    @Test
    public void buildingWithSingleHost() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        b.host("foo");
        assertThat(b.hosts()).containsExactly(InetSocketAddress.createUnresolved("foo", 36462));
    }

    @Test
    public void buildingWithMultipleHosts() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        b.host("foo", 1);
        b.host("bar", 2);
        assertThat(b.hosts()).containsExactlyInAnyOrder(
                InetSocketAddress.createUnresolved("foo", 1),
                InetSocketAddress.createUnresolved("bar", 2));
    }

    @Test
    public void profileWithPortProperties() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        b.profile("ports");
        assertThat(b.hosts()).containsExactlyInAnyOrder(
                InetSocketAddress.createUnresolved("alice.com", 8080),
                // bob.com defaults to 36462 because no port was specified.
                InetSocketAddress.createUnresolved("bob.com", 36462));

        // Can't change mind later.
        assertThatThrownBy(b::useTls).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void profileWithTlsPortProperties() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        b.useTls();
        b.profile("ports");
        assertThat(b.hosts()).containsExactlyInAnyOrder(
                InetSocketAddress.createUnresolved("alice.com", 8443),
                // bob.com defaults to 36462 because no port was specified.
                InetSocketAddress.createUnresolved("bob.com", 36462));

        // Can't change mind later.
        assertThatThrownBy(() -> b.useTls(false)).isInstanceOf(IllegalStateException.class);
    }

    private static final class CentralDogmaBuilder extends AbstractCentralDogmaBuilder<CentralDogmaBuilder> {}
}
