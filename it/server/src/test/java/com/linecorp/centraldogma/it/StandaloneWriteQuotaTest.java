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

package com.linecorp.centraldogma.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.UnknownHostException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class StandaloneWriteQuotaTest extends WriteQuotaTestBase {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.systemAdministrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
            // Default write quota
            builder.writeQuotaPerRepository(5, 1);
        }
    };

    private WebClient webClient;
    private CentralDogma dogmaClient;

    @BeforeEach
    void setUp() throws JsonProcessingException, UnknownHostException {
        final String adminSessionId = getSessionId(TestAuthMessageUtil.USERNAME, TestAuthMessageUtil.PASSWORD);
        final URI uri = dogma.httpClient().uri();

        webClient = WebClient.builder(uri)
                             .auth(AuthToken.ofOAuth2(adminSessionId))
                             .build();
        dogmaClient = new ArmeriaCentralDogmaBuilder()
                .accessToken(adminSessionId)
                .host(uri.getHost(), uri.getPort())
                .build();
    }

    @Override
    protected WebClient webClient() {
        return webClient;
    }

    @Override
    protected CentralDogma dogmaClient() {
        return dogmaClient;
    }

    private static String getSessionId(String username, String password) throws JsonProcessingException {
        final AggregatedHttpResponse response =
                TestAuthMessageUtil.login(dogma.httpClient(), username, password);

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        return Jackson.readValue(response.content().array(), AccessToken.class).accessToken();
    }
}
