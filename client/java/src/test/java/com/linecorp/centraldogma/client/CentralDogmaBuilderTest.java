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
package com.linecorp.centraldogma.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

class CentralDogmaBuilderTest {

    @Test
    void mutuallyExclusiveHostAndProfile() {
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
    void emptyProfile() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        assertThatThrownBy(() -> b.profile("bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no profile matches");
    }

    @Test
    void mismatchingProfile() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();

        assertThatThrownBy(() -> b.profile("none"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no profile matches");
    }

    @Test
    void httpProfile() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        b.profile("foo");
        assertThat(b.hosts()).containsExactlyInAnyOrder(
                InetSocketAddress.createUnresolved("foo.test.com", 36462));
    }

    @Test
    void httpsProfile() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        b.useTls();
        b.profile("foo");
        assertThat(b.hosts()).containsExactlyInAnyOrder(
                InetSocketAddress.createUnresolved("foo.test.com", 443));
    }

    @Test
    void profileLoadedFromAllResources() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        b.profile("production");
        assertThat(b.hosts()).containsExactlyInAnyOrder(
                InetSocketAddress.createUnresolved("prod1.com", 36462),
                InetSocketAddress.createUnresolved("prod2.com", 36462));
    }

    @Test
    void ipHosts() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        b.profile("ip_hosts");
        assertThat(b.hosts()).containsExactlyInAnyOrder(
                new InetSocketAddress("192.168.0.1", 8081),
                new InetSocketAddress("192.168.0.2", 8082));
    }

    @Test
    void profileWithHighPriorityWins() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        b.profile("high_priority_wins");
        assertThat(b.hosts()).containsExactlyInAnyOrder(
                InetSocketAddress.createUnresolved("high-priority.com", 36462));
    }

    @Test
    void profileWithLowPriorityLoses() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        b.profile("low_priority_loses");
        assertThat(b.hosts()).containsExactlyInAnyOrder(
                InetSocketAddress.createUnresolved("high-priority.com", 36462));
    }

    @Test
    void lastProfileFirst() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        // The last valid profile should win, to be consistent with Spring Boot profiles.
        b.profile("foo", "qux");
        assertThat(b.selectedProfile()).isEqualTo("qux");
        assertThat(b.hosts()).containsExactlyInAnyOrder(
                InetSocketAddress.createUnresolved("qux1.test.com", 36462),
                InetSocketAddress.createUnresolved("qux2.test.com", 36462));
    }

    @Test
    void singleHost() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        b.host("foo");
        assertThat(b.hosts()).containsExactly(InetSocketAddress.createUnresolved("foo", 36462));
    }

    @Test
    void tlsHostWithoutPort() {
        // useTls() before host()
        final CentralDogmaBuilder b1 = new CentralDogmaBuilder();
        b1.useTls();
        b1.host("foo");
        assertThat(b1.hosts()).containsExactly(InetSocketAddress.createUnresolved("foo", 443));

        // useTls() after host()
        final CentralDogmaBuilder b2 = new CentralDogmaBuilder();
        b2.host("foo");
        b2.useTls();
        assertThat(b2.hosts()).containsExactly(InetSocketAddress.createUnresolved("foo", 443));

        // An IP address without a port number
        final CentralDogmaBuilder b3 = new CentralDogmaBuilder();
        b3.host("192.168.0.1");
        b3.useTls();
        assertThat(b3.hosts()).containsExactly(new InetSocketAddress("192.168.0.1", 443));

        // An IPv6 address without a port number
        final CentralDogmaBuilder b4 = new CentralDogmaBuilder();
        b4.host("::1");
        b4.useTls();
        assertThat(b4.hosts()).containsExactly(new InetSocketAddress("::1", 443));

        // useTls(false) keeps the default cleartext port.
        final CentralDogmaBuilder b5 = new CentralDogmaBuilder();
        b5.host("foo");
        b5.useTls(false);
        assertThat(b5.hosts()).containsExactly(InetSocketAddress.createUnresolved("foo", 36462));
    }

    @Test
    void tlsHostWithExplicitPort() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        b.host("foo", 36462);
        b.useTls();
        assertThat(b.hosts()).containsExactly(InetSocketAddress.createUnresolved("foo", 36462));
    }

    @Test
    void tlsHostDeduplication() {
        // A host added with and without an explicit port collapses into one entry once resolved.
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        b.host("foo");
        b.host("foo", 443);
        b.useTls();
        assertThat(b.hosts()).containsExactly(InetSocketAddress.createUnresolved("foo", 443));
    }

    @Test
    void uriWithoutPort() {
        final CentralDogmaBuilder b1 = new CentralDogmaBuilder();
        b1.uri("tbinary+https://foo/cd/thrift/v1");
        b1.useTls();
        assertThat(b1.hosts()).containsExactly(InetSocketAddress.createUnresolved("foo", 443));

        final CentralDogmaBuilder b2 = new CentralDogmaBuilder();
        b2.uri("tbinary+http://foo/cd/thrift/v1");
        assertThat(b2.hosts()).containsExactly(InetSocketAddress.createUnresolved("foo", 36462));
    }

    @Test
    void multipleHosts() {
        final CentralDogmaBuilder b = new CentralDogmaBuilder();
        b.host("foo", 1);
        b.host("bar", 2);
        assertThat(b.hosts()).containsExactlyInAnyOrder(
                InetSocketAddress.createUnresolved("foo", 1),
                InetSocketAddress.createUnresolved("bar", 2));
    }

    private static final class CentralDogmaBuilder extends AbstractCentralDogmaBuilder<CentralDogmaBuilder> {}
}
