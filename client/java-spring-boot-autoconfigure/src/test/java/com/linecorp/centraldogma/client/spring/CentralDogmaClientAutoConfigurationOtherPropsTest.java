/*
 * Copyright 2019 LINE Corporation
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.spring.CentralDogmaClientAutoConfigurationSpringProfileTest.TestConfiguration;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "otherProps", "confTest" })
public class CentralDogmaClientAutoConfigurationOtherPropsTest {
    @Configuration
    @Import(CentralDogmaClientAutoConfiguration.class)
    public static class TestConfiguration {}

    @Inject
    private CentralDogma client;

    @Inject
    private CentralDogmaSettings settings;

    @Test
    public void centralDogmaClient() throws Exception {
        assertThat(client).isNotNull();
    }

    @Test
    public void settings() {
        assertThat(settings.getHosts()).isNull();
        assertThat(settings.getProfile()).isNull();
        assertThat(settings.getUseTls()).isTrue();
        assertThat(settings.getHealthCheckIntervalMillis()).isEqualTo(60000);
        assertThat(settings.getAccessToken()).isEqualTo("my-dogma-access-token");
    }
}
