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
package com.linecorp.centraldogma.server.internal.api;

import static com.linecorp.centraldogma.internal.api.v1.HttpApiV1Constants.API_V1_PATH_PREFIX;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

class AppIdentityRegistryServiceViaHttpTest {

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.systemAdministrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }
    };

    private static WebClient systemAdminClient;

    @BeforeAll
    static void setUp() throws JsonMappingException, JsonParseException {
        final URI uri = dogma.httpClient().uri();
        systemAdminClient = WebClient.builder(uri)
                                     .auth(AuthToken.ofOAuth2(getAccessToken(dogma.httpClient(),
                                                                             TestAuthMessageUtil.USERNAME,
                                                                             TestAuthMessageUtil.PASSWORD,
                                                                             true)))
                                     .build();
    }

    @Test
    void createTokenAndUpdateLevel() throws JsonParseException {
        assertThat(systemAdminClient.post(API_V1_PATH_PREFIX + "appIdentities",
                                          QueryParams.of("appId", "forUpdate", "type", "TOKEN",
                                                         "isSystemAdmin", false),
                                          HttpData.empty())
                                    .aggregate()
                                    .join()
                                    .headers()
                                    .get(HttpHeaderNames.LOCATION)).isEqualTo("/appIdentities/forUpdate");

        RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH,
                                                   API_V1_PATH_PREFIX + "appIdentities/forUpdate/level",
                                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);

        final String body = "{\"level\":\"SYSTEMADMIN\"}";
        AggregatedHttpResponse response = systemAdminClient.execute(headers, body).aggregate().join();

        final JsonNode jsonNode = Jackson.readTree(response.contentUtf8());
        assertThat(jsonNode.get("appId").asText()).isEqualTo("forUpdate");
        assertThat(jsonNode.get("systemAdmin").asBoolean()).isEqualTo(true);

        response = systemAdminClient.execute(headers, body).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.NOT_MODIFIED);

        headers = RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "appIdentities/forUpdate",
                                    HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        response = systemAdminClient.execute(headers, "{\"status\":\"inactive\"}").aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).contains("\"deactivation\":");

        headers = RequestHeaders.of(HttpMethod.PATCH, API_V1_PATH_PREFIX + "appIdentities/forUpdate",
                                    HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        response = systemAdminClient.execute(headers, "{\"status\":\"active\"}").aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).doesNotContain("\"deactivation\":");
    }
}
