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
import static org.awaitility.Awaitility.await;

import java.net.URI;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
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
    void regenerateTokenSecret() throws JsonProcessingException {
        final AggregatedHttpResponse createResponse =
                systemAdminClient.post(API_V1_PATH_PREFIX + "appIdentities",
                                       QueryParams.of("appId", "forRegenerate", "type", "TOKEN",
                                                      "isSystemAdmin", false),
                                       HttpData.empty())
                                 .aggregate()
                                 .join();
        assertThat(createResponse.status()).isEqualTo(HttpStatus.CREATED);
        final String oldSecret = Jackson.readTree(createResponse.contentUtf8()).get("secret").asText();

        // The old secret authenticates requests.
        assertThat(newTokenClient(oldSecret).get(API_V1_PATH_PREFIX + "appIdentities").aggregate().join()
                                            .status()).isEqualTo(HttpStatus.OK);

        final AggregatedHttpResponse regenerateResponse =
                systemAdminClient.post(API_V1_PATH_PREFIX + "appIdentities/forRegenerate/secret",
                                       HttpData.empty())
                                 .aggregate()
                                 .join();
        assertThat(regenerateResponse.status()).isEqualTo(HttpStatus.OK);
        final JsonNode regenerated = Jackson.readTree(regenerateResponse.contentUtf8());
        assertThat(regenerated.get("appId").asText()).isEqualTo("forRegenerate");
        final String newSecret = regenerated.get("secret").asText();
        assertThat(newSecret).startsWith("appToken-")
                             .isNotEqualTo(oldSecret);

        // The old secret is revoked and the new secret authenticates requests.
        // The registry used by the authorizer is updated asynchronously so await the changes.
        await().untilAsserted(() -> {
            assertThat(newTokenClient(oldSecret).get(API_V1_PATH_PREFIX + "appIdentities").aggregate().join()
                                                .status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(newTokenClient(newSecret).get(API_V1_PATH_PREFIX + "appIdentities").aggregate().join()
                                                .status()).isEqualTo(HttpStatus.OK);
        });
    }

    @Test
    void ownerCanRegenerateOwnTokenSecret() throws JsonProcessingException {
        // A non-admin user creates its own token and regenerates its secret.
        final WebClient userClient =
                WebClient.builder(dogma.httpClient().uri())
                         .auth(AuthToken.ofOAuth2(getAccessToken(dogma.httpClient(),
                                                                 TestAuthMessageUtil.USERNAME2,
                                                                 TestAuthMessageUtil.PASSWORD2,
                                                                 "ownerAppId",
                                                                 false)))
                         .build();
        assertThat(userClient.post(API_V1_PATH_PREFIX + "appIdentities",
                                   QueryParams.of("appId", "ownedByUser2", "type", "TOKEN",
                                                  "isSystemAdmin", false),
                                   HttpData.empty())
                             .aggregate()
                             .join()
                             .status()).isEqualTo(HttpStatus.CREATED);

        final AggregatedHttpResponse response =
                userClient.post(API_V1_PATH_PREFIX + "appIdentities/ownedByUser2/secret", HttpData.empty())
                          .aggregate()
                          .join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void regenerateSecretOfDeactivatedTokenViaHttp() throws JsonProcessingException {
        assertThat(systemAdminClient.post(API_V1_PATH_PREFIX + "appIdentities",
                                          QueryParams.of("appId", "forInactive", "type", "TOKEN",
                                                         "isSystemAdmin", false),
                                          HttpData.empty())
                                    .aggregate()
                                    .join()
                                    .status()).isEqualTo(HttpStatus.CREATED);
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH,
                                                         API_V1_PATH_PREFIX + "appIdentities/forInactive",
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);
        assertThat(systemAdminClient.execute(headers, "{\"status\":\"inactive\"}").aggregate().join()
                                    .status()).isEqualTo(HttpStatus.OK);

        final AggregatedHttpResponse response =
                systemAdminClient.post(API_V1_PATH_PREFIX + "appIdentities/forInactive/secret",
                                       HttpData.empty())
                                 .aggregate()
                                 .join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        final JsonNode regenerated = Jackson.readTree(response.contentUtf8());
        final String newSecret = regenerated.get("secret").asText();
        assertThat(newSecret).startsWith("appToken-");
        // The token remains deactivated so the new secret does not authenticate until activation.
        assertThat(regenerated.get("deactivation")).isNotNull();
        await().untilAsserted(() -> {
            assertThat(newTokenClient(newSecret).get(API_V1_PATH_PREFIX + "appIdentities").aggregate().join()
                                                .status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        });
    }

    @Test
    void cannotRegenerateSecretOfMissingToken() {
        assertThat(systemAdminClient.post(API_V1_PATH_PREFIX + "appIdentities/nonexistent/secret",
                                          HttpData.empty())
                                    .aggregate()
                                    .join()
                                    .status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void cannotRegenerateSecretWithoutPermission() {
        assertThat(systemAdminClient.post(API_V1_PATH_PREFIX + "appIdentities",
                                          QueryParams.of("appId", "ownedBySystemAdmin", "type", "TOKEN",
                                                         "isSystemAdmin", false),
                                          HttpData.empty())
                                    .aggregate()
                                    .join()
                                    .status()).isEqualTo(HttpStatus.CREATED);

        final WebClient userClient =
                WebClient.builder(dogma.httpClient().uri())
                         .auth(AuthToken.ofOAuth2(getAccessToken(dogma.httpClient(),
                                                                 TestAuthMessageUtil.USERNAME2,
                                                                 TestAuthMessageUtil.PASSWORD2,
                                                                 "appIdOfUser2",
                                                                 false)))
                         .build();
        assertThat(userClient.post(API_V1_PATH_PREFIX + "appIdentities/ownedBySystemAdmin/secret",
                                   HttpData.empty())
                             .aggregate()
                             .join()
                             .status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void cannotRegenerateSecretOfDestroyedToken() {
        assertThat(systemAdminClient.post(API_V1_PATH_PREFIX + "appIdentities",
                                          QueryParams.of("appId", "forDestroyed", "type", "TOKEN",
                                                         "isSystemAdmin", false),
                                          HttpData.empty())
                                    .aggregate()
                                    .join()
                                    .status()).isEqualTo(HttpStatus.CREATED);
        // The DELETE method always responds with 204 No Content on success.
        assertThat(systemAdminClient.delete(API_V1_PATH_PREFIX + "appIdentities/forDestroyed")
                                    .aggregate()
                                    .join()
                                    .status()).isEqualTo(HttpStatus.NO_CONTENT);

        final AggregatedHttpResponse response =
                systemAdminClient.post(API_V1_PATH_PREFIX + "appIdentities/forDestroyed/secret",
                                       HttpData.empty())
                                 .aggregate()
                                 .join();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).contains("scheduled for deletion");
    }

    private static WebClient newTokenClient(String secret) {
        return WebClient.builder(dogma.httpClient().uri())
                        .auth(AuthToken.ofOAuth2(secret))
                        .build();
    }

    @Test
    void createTokenAndUpdateLevel() throws JsonProcessingException {
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
