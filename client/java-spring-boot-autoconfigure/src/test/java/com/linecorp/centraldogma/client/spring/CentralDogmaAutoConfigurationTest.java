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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.spring.CentralDogmaAutoConfigurationTest.TestConfiguration;
import com.linecorp.centraldogma.client.spring.CentralDogmaClientAutoConfiguration.ForCentralDogma;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "confTest" })
public class CentralDogmaAutoConfigurationTest {
    @SpringBootApplication
    public static class TestConfiguration {
        static final ClientFactory clientFactoryNotForCentralDogma = new ClientFactoryBuilder().build();

        @Bean
        public ClientFactory clientFactory() {
            return clientFactoryNotForCentralDogma;
        }
    }

    @Inject
    CentralDogma client;

    @Inject
    @ForCentralDogma
    ClientFactory clientFactory;

    @Test
    public void centralDogmaClient() throws Exception {
        assertThat(client).isNotNull();
        assertThat(clientFactory).isSameAs(ClientFactory.DEFAULT);
    }
}
