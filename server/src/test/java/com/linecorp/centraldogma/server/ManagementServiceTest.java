/*
 * Copyright 2024 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.util.PortUtil;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class ManagementServiceTest {

    private static int tlsPort;

    @RegisterExtension
    static final CentralDogmaExtension noManagement = new CentralDogmaExtension();

    @RegisterExtension
    static final CentralDogmaExtension management = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.management(new ManagementConfig((String) null, null, 0, null));
        }
    };

    @RegisterExtension
    static final CentralDogmaExtension managementWithFullOptions = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            tlsPort = PortUtil.unusedTcpPort();
            builder.management(
                    new ManagementConfig(SessionProtocol.HTTPS, "127.0.0.1", tlsPort, "/custom/management"));
        }
    };

    @Test
    void disableManagementServiceByDefault() {
        final BlockingWebClient client = noManagement.blockingHttpClient();
        assertThat(client.get("/internal/management").status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void managementServiceWithDefaultOption() {
        final BlockingWebClient client = management.blockingHttpClient();
        final AggregatedHttpResponse response = client.get("/internal/management/jvm/threaddump");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).contains("repository-worker-");
    }

    @Test
    void managementServiceWithFullOptions() {
        final BlockingWebClient client =
                WebClient.builder("https://127.0.0.1:" + tlsPort)
                         .factory(ClientFactory.insecure())
                         .build()
                         .blocking();
        final AggregatedHttpResponse response = client.get("/custom/management/jvm/threaddump");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).contains("repository-worker-");
    }

    @Test
    void testJsonDeserialization() throws JsonProcessingException {
        final String json =
                '{' +
                "\"protocol\":\"https\"," +
                "\"address\":\"127.0.0.1\"," +
                "\"port\":8443," +
                "\"path\":\"/custom/management\"" +
                '}';
        final ManagementConfig managementConfig = Jackson.readValue(json, ManagementConfig.class);

        assertThat(managementConfig.protocol()).isEqualTo(SessionProtocol.HTTPS);
        assertThat(managementConfig.port()).isEqualTo(8443);
        assertThat(managementConfig.address()).isEqualTo("127.0.0.1");
        assertThat(managementConfig.path()).isEqualTo("/custom/management");
    }
}
