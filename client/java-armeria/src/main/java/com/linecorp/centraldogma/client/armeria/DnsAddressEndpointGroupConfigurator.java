/*
 * Copyright 2021 LINE Corporation
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

import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroupBuilder;
import com.linecorp.centraldogma.client.CentralDogma;

/**
 * Configures the DNS resolution of the <a href="https://line.github.io/armeria/">Armeria</a> client of
 * {@link CentralDogma}.
 */
@FunctionalInterface
public interface DnsAddressEndpointGroupConfigurator {
    /**
     * Configures the DNS lookup of the {@link ArmeriaCentralDogma} client.
     */
    void configure(DnsAddressEndpointGroupBuilder dnsAddressEndpointGroupBuilder);
}
