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
package com.linecorp.centraldogma.client.spring;

import static org.assertj.core.api.Assertions.assertThat;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.spring.CentralDogmaAutoConfigurationTest.TestConfiguration;
import com.linecorp.centraldogma.client.spring.CentralDogmaClientAutoConfiguration.ForCentralDogma;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "confTest" })
class CentralDogmaAutoConfigurationTest {
    @SpringBootApplication
    static class TestConfiguration {
        static final ClientFactory clientFactoryNotForCentralDogma = ClientFactory.builder().build();

        @Bean
        ClientFactory clientFactory() {
            return clientFactoryNotForCentralDogma;
        }
    }

    @Inject
    private CentralDogma client;

    @Inject
    @ForCentralDogma
    private ClientFactory clientFactory;

    /**
     * When there are no `ClientFactory`s with `ForCentralDogma` qualifier,
     * the default `ClientFactory` must be used.
     */
    @Test
    void centralDogmaClient() throws Exception {
        assertThat(client).isNotNull();

        if (SpringBootVersion.getVersion().startsWith("1.")) {
            // JUnit 5 extension for Spring Boot 1.x has a bug which pulls in a bean from other tests,
            // so we can't test this properly.
            final ClientFactory expectedClientFactory =
                    new CentralDogmaClientAutoConfigurationWithClientFactoryTest.TestConfiguration()
                            .dogmaClientFactory();
            assertThat(clientFactory).isSameAs(expectedClientFactory);
        } else {
            assertThat(clientFactory).isSameAs(ClientFactory.ofDefault());
        }
    }
}
