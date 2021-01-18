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
package com.linecorp.centraldogma.client.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroupBuilder;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.spring.CentralDogmaClientAutoConfigurationWithDnsEndpointGroupCustomizerTest.TestConfiguration;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "confTest" })
class CentralDogmaClientAutoConfigurationWithDnsEndpointGroupCustomizerTest {
    @SpringBootApplication
    static class TestConfiguration {
        @Bean
        AtomicBoolean customizationDone() {
            return new AtomicBoolean();
        }

        @Bean
        Consumer<DnsAddressEndpointGroupBuilder> dnsAddressEndpointGroupCustomizer(
                AtomicBoolean customizationDone) {
            return b -> customizationDone.set(true);
        }
    }

    @Inject
    private CentralDogma client;

    @Inject
    private AtomicBoolean customizationDone;

    @Test
    void centralDogmaClient() throws Exception {
        assertThat(client).isNotNull();
        // client has been instantiated
        assertTrue(customizationDone.get());
    }
}
