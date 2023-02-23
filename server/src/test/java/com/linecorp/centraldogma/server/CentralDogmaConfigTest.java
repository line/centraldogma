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
package com.linecorp.centraldogma.server;

import static com.linecorp.centraldogma.server.CentralDogmaBuilder.DEFAULT_MAX_REMOVED_REPOSITORY_AGE_MILLIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.server.ClientAddressSource;
import com.linecorp.centraldogma.internal.Jackson;

class CentralDogmaConfigTest {

    @Test
    void trustedProxyAddress_withCustomClientAddressSources() throws Exception {
        final CentralDogmaConfig cfg =
                Jackson.readValue("{\n" +
                                  "  \"dataDir\": \"./data\",\n" +
                                  "  \"ports\": [\n" +
                                  "    {\n" +
                                  "      \"localAddress\": {\n" +
                                  "        \"host\": \"*\",\n" +
                                  "        \"port\": 36462\n" +
                                  "      },\n" +
                                  "      \"protocols\": [\n" +
                                  "        \"https\",\n" +
                                  "        \"http\",\n" +
                                  "        \"proxy\"\n" +
                                  "      ]\n" +
                                  "    }\n" +
                                  "  ],\n" +
                                  "  \"trustedProxyAddresses\": [\n" +
                                  "    \"10.0.0.1\",\n" +
                                  "    \"11.0.0.1/24\"\n" +
                                  "  ],\n" +
                                  "  \"clientAddressSources\": [\n" +
                                  "    \"custom-client-address-header\",\n" +
                                  "    \"PROXY_PROTOCOL\"\n" +
                                  "  ]\n" +
                                  '}',
                                  CentralDogmaConfig.class);
        assertThat(cfg.trustedProxyAddresses()).hasSize(2);
        assertThat(cfg.clientAddressSources()).hasSize(2);

        final Predicate<InetAddress> p = cfg.trustedProxyAddressPredicate();
        assertThat(p.test(InetAddress.getByName("10.0.0.1"))).isTrue();
        assertThat(p.test(InetAddress.getByName("10.0.0.2"))).isFalse();
        assertThat(p.test(InetAddress.getByName("11.0.0.1"))).isTrue();
        assertThat(p.test(InetAddress.getByName("11.0.1.1"))).isFalse();

        final List<ClientAddressSource> sources = cfg.clientAddressSourceList();
        assertThat(sources).hasSize(2);
        // TODO(hyangtack) Need to add equals/hashCode to ClientAddressSource class.
        //                 https://github.com/line/armeria/pull/1804
        assertThat(sources.get(0).toString())
                .isEqualTo(ClientAddressSource.ofHeader("custom-client-address-header").toString());
        assertThat(sources.get(1).toString()).isEqualTo(ClientAddressSource.ofProxyProtocol().toString());
    }

    @Test
    void trustedProxyAddress_withDefaultClientAddressSources() throws Exception {
        final CentralDogmaConfig cfg =
                Jackson.readValue("{\n" +
                                  "  \"dataDir\": \"./data\",\n" +
                                  "  \"ports\": [\n" +
                                  "    {\n" +
                                  "      \"localAddress\": {\n" +
                                  "        \"host\": \"*\",\n" +
                                  "        \"port\": 36462\n" +
                                  "      },\n" +
                                  "      \"protocols\": [\n" +
                                  "        \"https\",\n" +
                                  "        \"http\",\n" +
                                  "        \"proxy\"\n" +
                                  "      ]\n" +
                                  "    }\n" +
                                  "  ],\n" +
                                  "  \"trustedProxyAddresses\": [\n" +
                                  "    \"10.0.0.1\",\n" +
                                  "    \"11.0.0.1/24\"\n" +
                                  "  ]\n" +
                                  '}',
                                  CentralDogmaConfig.class);
        assertThat(cfg.trustedProxyAddresses()).hasSize(2);
        assertThat(cfg.clientAddressSources()).isNull();

        final List<ClientAddressSource> sources = cfg.clientAddressSourceList();
        assertThat(sources).hasSize(3);
        // TODO(hyangtack) Need to add equals/hashCode to ClientAddressSource class.
        //                 https://github.com/line/armeria/pull/1804
        assertThat(sources.get(0).toString()).isEqualTo(ClientAddressSource.ofHeader("forwarded").toString());
        assertThat(sources.get(1).toString()).isEqualTo(
                ClientAddressSource.ofHeader("x-forwarded-for").toString());
        assertThat(sources.get(2).toString()).isEqualTo(ClientAddressSource.ofProxyProtocol().toString());
    }

    @Test
    void trustedProxyAddress_withDefaultClientAddressSources_withoutProxyProtocol() throws Exception {
        final CentralDogmaConfig cfg =
                Jackson.readValue("{\n" +
                                  "  \"dataDir\": \"./data\",\n" +
                                  "  \"ports\": [\n" +
                                  "    {\n" +
                                  "      \"localAddress\": {\n" +
                                  "        \"host\": \"*\",\n" +
                                  "        \"port\": 36462\n" +
                                  "      },\n" +
                                  "      \"protocols\": [\n" +
                                  "        \"https\",\n" +
                                  "        \"http\"\n" +
                                  "      ]\n" +
                                  "    }\n" +
                                  "  ],\n" +
                                  "  \"trustedProxyAddresses\": [\n" +
                                  "    \"10.0.0.1\",\n" +
                                  "    \"11.0.0.1/24\"\n" +
                                  "  ]\n" +
                                  '}',
                                  CentralDogmaConfig.class);
        assertThat(cfg.trustedProxyAddresses()).hasSize(2);
        assertThat(cfg.clientAddressSources()).isNull();

        final List<ClientAddressSource> sources = cfg.clientAddressSourceList();
        assertThat(sources).hasSize(2);
        assertThat(sources.get(0)).isEqualTo(ClientAddressSource.ofHeader("forwarded"));
        assertThat(sources.get(1)).isEqualTo(ClientAddressSource.ofHeader("x-forwarded-for"));
    }

    @Test
    void noTrustedProxyAddress() throws Exception {
        final CentralDogmaConfig cfg =
                Jackson.readValue("{\n" +
                                  "  \"dataDir\": \"./data\",\n" +
                                  "  \"ports\": [\n" +
                                  "    {\n" +
                                  "      \"localAddress\": {\n" +
                                  "        \"host\": \"*\",\n" +
                                  "        \"port\": 36462\n" +
                                  "      },\n" +
                                  "      \"protocols\": [\n" +
                                  "        \"https\",\n" +
                                  "        \"http\",\n" +
                                  "        \"proxy\"\n" +
                                  "      ]\n" +
                                  "    }\n" +
                                  "  ]\n" +
                                  '}',
                                  CentralDogmaConfig.class);
        assertThat(cfg.trustedProxyAddresses()).isNull();
        assertThat(cfg.clientAddressSources()).isNull();
        assertThat(cfg.clientAddressSourceList()).isEmpty();
    }

    @Test
    void maxRemovedRepositoryAgeMillis() throws Exception {
        final CentralDogmaConfig cfg =
                Jackson.readValue("{\n" +
                                  "  \"dataDir\": \"./data\",\n" +
                                  "  \"ports\": [\n" +
                                  "    {\n" +
                                  "      \"localAddress\": {\n" +
                                  "        \"host\": \"*\",\n" +
                                  "        \"port\": 36462\n" +
                                  "      },\n" +
                                  "      \"protocols\": [\n" +
                                  "        \"https\",\n" +
                                  "        \"http\",\n" +
                                  "        \"proxy\"\n" +
                                  "      ]\n" +
                                  "    }\n" +
                                  "  ],\n" +
                                  "  \"maxRemovedRepositoryAgeMillis\": 50000 \n" +
                                  '}',
                                  CentralDogmaConfig.class);
        assertThat(cfg.maxRemovedRepositoryAgeMillis()).isEqualTo(50000);
    }

    @Test
    void maxRemovedRepositoryAgeMillis_withDefault() throws Exception {
        final CentralDogmaConfig cfg =
                Jackson.readValue("{\n" +
                                  "  \"dataDir\": \"./data\",\n" +
                                  "  \"ports\": [\n" +
                                  "    {\n" +
                                  "      \"localAddress\": {\n" +
                                  "        \"host\": \"*\",\n" +
                                  "        \"port\": 36462\n" +
                                  "      },\n" +
                                  "      \"protocols\": [\n" +
                                  "        \"https\",\n" +
                                  "        \"http\",\n" +
                                  "        \"proxy\"\n" +
                                  "      ]\n" +
                                  "    }\n" +
                                  "  ]\n" +
                                  '}',
                                  CentralDogmaConfig.class);
        assertThat(cfg.maxRemovedRepositoryAgeMillis()).isEqualTo(DEFAULT_MAX_REMOVED_REPOSITORY_AGE_MILLIS);
    }

    @Test
    void maxRemovedRepositoryAgeMillis_withNegativeValue() {
        assertThatThrownBy(() ->
                Jackson.readValue("{\n" +
                                  "  \"dataDir\": \"./data\",\n" +
                                  "  \"ports\": [\n" +
                                  "    {\n" +
                                  "      \"localAddress\": {\n" +
                                  "        \"host\": \"*\",\n" +
                                  "        \"port\": 36462\n" +
                                  "      },\n" +
                                  "      \"protocols\": [\n" +
                                  "        \"https\",\n" +
                                  "        \"http\",\n" +
                                  "        \"proxy\"\n" +
                                  "      ]\n" +
                                  "    }\n" +
                                  "  ],\n" +
                                  "  \"maxRemovedRepositoryAgeMillis\": -50000 \n" +
                                  '}',
                                  CentralDogmaConfig.class)
        ).hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void corsAllowedOrigins() throws Exception {
        final CentralDogmaConfig cfg =
                Jackson.readValue("{\n" +
                                  "  \"dataDir\": \"./data\",\n" +
                                  "  \"ports\": [\n" +
                                  "    {\n" +
                                  "      \"localAddress\": {\n" +
                                  "        \"host\": \"*\",\n" +
                                  "        \"port\": 36462\n" +
                                  "      },\n" +
                                  "      \"protocols\": [\n" +
                                  "        \"https\",\n" +
                                  "        \"http\",\n" +
                                  "        \"proxy\"\n" +
                                  "      ]\n" +
                                  "    }\n" +
                                  "  ],\n" +
                                  "  \"corsAllowedOrigins\": [" +
                                  "      \"sample.com\"\n" +
                                  "    ]" +
                                  '}',
                                  CentralDogmaConfig.class);
        assertThat(cfg.corsAllowedOrigins() != null ? cfg.corsAllowedOrigins().get(0)
                                                    : null).isEqualTo("sample.com");
    }
}
