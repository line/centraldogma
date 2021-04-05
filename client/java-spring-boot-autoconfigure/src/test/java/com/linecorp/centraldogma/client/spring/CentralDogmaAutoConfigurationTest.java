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

import java.util.List;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.spring.CentralDogmaAutoConfigurationTest.TestConfiguration;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "confTest" })
class CentralDogmaAutoConfigurationTest {
    @SpringBootApplication
    static class TestConfiguration {
        static CentralDogmaClientFactoryConfigurator factoryConfigurator = builder -> {};

        @Bean
        CentralDogmaClientFactoryConfigurator configurator() {
            return factoryConfigurator;
        }
    }

    @Inject
    private CentralDogma client;

    @Inject
    List<CentralDogmaClientFactoryConfigurator> configurators;

    /**
     * When there are no `ClientFactory`s with `ForCentralDogma` qualifier,
     * the default `ClientFactory` must be used.
     */
    @Test
    void centralDogmaClient() {
        assertThat(client).isNotNull();
        assertThat(configurators).containsExactly(TestConfiguration.factoryConfigurator);
    }
}
