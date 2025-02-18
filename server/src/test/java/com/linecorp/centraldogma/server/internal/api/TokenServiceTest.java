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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.util.Collection;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ProjectRole;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.api.sysadmin.TokenLevelRequest;
import com.linecorp.centraldogma.server.internal.api.sysadmin.TokenService;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.Token;
import com.linecorp.centraldogma.server.metadata.Tokens;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;
import com.linecorp.centraldogma.testing.junit.CentralDogmaExtension;

import io.netty.util.internal.StringUtil;

class TokenServiceTest {

    @RegisterExtension
    static final ProjectManagerExtension manager = new ProjectManagerExtension();

    @RegisterExtension
    static final CentralDogmaExtension dogma = new CentralDogmaExtension() {

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.systemAdministrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }
    };

    private static final Author systemAdminAuthor = Author.ofEmail("systemAdmin@localhost.com");
    private static final Author guestAuthor = Author.ofEmail("guest@localhost.com");
    private static final User systemAdmin = new User("systemAdmin@localhost.com", User.LEVEL_SYSTEM_ADMIN);
    private static final User guest = new User("guest@localhost.com");
    private static final JsonNode activation = Jackson.valueToTree(
            ImmutableList.of(
                    ImmutableMap.of("op", "replace",
                                    "path", "/status",
                                    "value", "active")));
    private static final JsonNode deactivation = Jackson.valueToTree(
            ImmutableList.of(
                    ImmutableMap.of("op", "replace",
                                    "path", "/status",
                                    "value", "inactive")));

    private static TokenService tokenService;
    private static MetadataService metadataService;
    private static WebClient systemAdminClient;

    // ctx is only used for getting the blocking task executor.
    private final ServiceRequestContext ctx =
            ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();

    static String sessionId(WebClient webClient, String username, String password)
            throws JsonParseException, JsonMappingException {
        return Jackson.readValue(TestAuthMessageUtil.login(webClient, username, password).content().array(),
                                 AccessToken.class).accessToken();
    }

    @BeforeAll
    static void setUp() throws JsonMappingException, JsonParseException {
        final URI uri = dogma.httpClient().uri();
        systemAdminClient = WebClient.builder(uri)
                                     .auth(AuthToken.ofOAuth2(sessionId(dogma.httpClient(),
                                                                        TestAuthMessageUtil.USERNAME,
                                                                        TestAuthMessageUtil.PASSWORD)))
                                     .build();
        metadataService = new MetadataService(manager.projectManager(), manager.executor(),
                                              manager.internalProjectInitializer());
        tokenService = new TokenService(manager.executor(), metadataService);
    }

    @AfterEach
    public void tearDown() {
        final Tokens tokens = metadataService.fetchTokens().join();
        tokens.appIds().forEach((appId, token) -> {
            if (!token.isDeleted()) {
                metadataService.destroyToken(systemAdminAuthor, appId);
            }
            metadataService.purgeToken(systemAdminAuthor, appId);
        });
    }

    @Test
    void systemAdminToken() {
        final Token token = tokenService.createToken("forAdmin1", true, true, null,
                                                     systemAdminAuthor, systemAdmin).join()
                                        .content();
        assertThat(token.isActive()).isTrue();
        assertThatThrownBy(() -> tokenService.createToken("forAdmin2", true, true, null, guestAuthor, guest)
                                             .join())
                .isInstanceOf(IllegalArgumentException.class);

        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) manager.executor();
        executor.execute(Command.createProject(Author.SYSTEM, "myPro")).join();
        metadataService.addToken(Author.SYSTEM, "myPro", "forAdmin1", ProjectRole.OWNER).join();
        await().untilAsserted(() -> assertThat(metadataService.getProject("myPro").join().tokens()
                                                              .containsKey("forAdmin1")).isTrue());

        final Collection<Token> tokens = tokenService.listTokens(systemAdmin);
        assertThat(tokens.stream().filter(t -> !StringUtil.isNullOrEmpty(t.secret()))).hasSize(1);

        assertThatThrownBy(() -> tokenService.deleteToken(ctx, "forAdmin1", guestAuthor, guest)
                                             .join())
                .hasCauseInstanceOf(HttpResponseException.class);

        tokenService.deleteToken(ctx, "forAdmin1", systemAdminAuthor, systemAdmin).join();
        assertThat(tokenService.purgeToken(ctx, "forAdmin1", systemAdminAuthor, systemAdmin).join())
                .satisfies(t -> {
                    assertThat(t.appId()).isEqualTo(token.appId());
                    assertThat(t.isSystemAdmin()).isEqualTo(token.isSystemAdmin());
                    assertThat(t.creation()).isEqualTo(token.creation());
                    assertThat(t.isDeleted()).isTrue();
                });
        await().untilAsserted(() -> assertThat(tokenService.listTokens(systemAdmin).size()).isEqualTo(0));
        assertThat(metadataService.getProject("myPro").join().tokens().size()).isEqualTo(0);
    }

    @Test
    void userToken() {
        final Token userToken1 = tokenService.createToken("forUser1", false, false, null, systemAdminAuthor,
                                                          systemAdmin)
                                             .join().content();
        final Token userToken2 = tokenService.createToken("forUser2", false, false, null, guestAuthor, guest)
                                             .join().content();
        assertThat(userToken1.isActive()).isTrue();
        assertThat(userToken2.isActive()).isTrue();

        final Collection<Token> tokens = tokenService.listTokens(guest);
        assertThat(tokens.stream().filter(token -> !StringUtil.isNullOrEmpty(token.secret())).count())
                .isEqualTo(0);

        assertThat(tokenService.deleteToken(ctx, "forUser1", systemAdminAuthor, systemAdmin)
                               .thenCompose(unused -> tokenService.purgeToken(
                                       ctx, "forUser1", systemAdminAuthor, systemAdmin)).join())
                .satisfies(t -> {
                    assertThat(t.appId()).isEqualTo(userToken1.appId());
                    assertThat(t.isSystemAdmin()).isEqualTo(userToken1.isSystemAdmin());
                    assertThat(t.creation()).isEqualTo(userToken1.creation());
                    assertThat(t.deactivation()).isEqualTo(userToken1.deactivation());
                });
        assertThat(tokenService.deleteToken(ctx, "forUser2", guestAuthor, guest)
                               .thenCompose(unused -> tokenService.purgeToken(
                                       ctx, "forUser2", guestAuthor, guest)).join())
                .satisfies(t -> {
                    assertThat(t.appId()).isEqualTo(userToken2.appId());
                    assertThat(t.isSystemAdmin()).isEqualTo(userToken2.isSystemAdmin());
                    assertThat(t.creation()).isEqualTo(userToken2.creation());
                    assertThat(t.deactivation()).isEqualTo(userToken2.deactivation());
                });
    }

    @Test
    void nonRandomToken() {
        final Collection<Token> tokens1 = tokenService.listTokens(systemAdmin);
        assertThat(tokens1.stream().filter(t -> !StringUtil.isNullOrEmpty(t.secret()))).hasSize(0);
        final Token token = tokenService.createToken("forAdmin1", true, true, "appToken-secret",
                                                     systemAdminAuthor,
                                                     systemAdmin)
                                        .join().content();
        assertThat(token.isActive()).isTrue();

        final Collection<Token> tokens = tokenService.listTokens(systemAdmin);
        assertThat(tokens.stream().filter(t -> !StringUtil.isNullOrEmpty(t.secret()))).hasSize(1);

        assertThatThrownBy(() -> tokenService.createToken("forUser1", true, true,
                                                          "appToken-secret", guestAuthor, guest)
                                             .join())
                .isInstanceOf(IllegalArgumentException.class);

        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.DELETE, "/tokens/{appId}/removed"));
        System.err.println(tokenService.listTokens(systemAdmin));
        final Token forAdmin1 = tokenService.deleteToken(ctx, "forAdmin1", systemAdminAuthor, systemAdmin)
                                            .join();
        System.err.println(forAdmin1);
        tokenService.purgeToken(ctx, "forAdmin1", systemAdminAuthor, systemAdmin).join();
    }

    @Test
    public void updateToken() {
        final Token token = tokenService.createToken("forUpdate", true, true, null,
                                                     systemAdminAuthor, systemAdmin).join()
                                        .content();
        assertThat(token.isActive()).isTrue();

        tokenService.updateToken(ctx, "forUpdate", deactivation, systemAdminAuthor, systemAdmin).join();
        await().untilAsserted(() -> assertThat(metadataService.findTokenByAppId("forUpdate").isActive())
                .isFalse());

        tokenService.updateToken(ctx, "forUpdate", activation, systemAdminAuthor, systemAdmin).join();
        await().untilAsserted(() -> assertThat(metadataService.findTokenByAppId("forUpdate").isActive())
                .isTrue());

        assertThatThrownBy(
                () -> tokenService.updateToken(ctx, "forUpdate", Jackson.valueToTree(
                        ImmutableList.of(ImmutableMap.of())), systemAdminAuthor, systemAdmin).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);

        tokenService.deleteToken(ctx, "forUpdate", systemAdminAuthor, systemAdmin).join();
        await().untilAsserted(() -> assertThat(metadataService.findTokenByAppId("forUpdate").isDeleted())
                .isTrue());
        assertThatThrownBy(
                () -> tokenService.updateToken(ctx, "forUpdate", activation,
                                               systemAdminAuthor, systemAdmin).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateTokenLevel() {
        final Token token = tokenService.createToken("forUpdate", false, false, null,
                                                     systemAdminAuthor, systemAdmin).join()
                                        .content();
        assertThat(token.isActive()).isTrue();

        final Token userToken = tokenService.updateTokenLevel(
                                                    ctx, "forUpdate", new TokenLevelRequest("SYSTEMADMIN"),
                                                    systemAdminAuthor, systemAdmin)
                                            .join();
        assertThat(userToken.isSystemAdmin()).isTrue();

        final Token adminToken = tokenService.updateTokenLevel(ctx, "forUpdate", new TokenLevelRequest("USER"),
                                                               systemAdminAuthor, systemAdmin)
                                             .join();
        assertThat(adminToken.isSystemAdmin()).isFalse();

        assertThatThrownBy(
                () -> tokenService.updateTokenLevel(ctx, "forUpdate", new TokenLevelRequest("INVALID"),
                                                    systemAdminAuthor, systemAdmin).join())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createTokenAndUpdateLevel() throws JsonParseException {
        assertThat(systemAdminClient.post(API_V1_PATH_PREFIX + "tokens",
                                          QueryParams.of("appId", "forUpdate", "isSystemAdmin", false),
                                          HttpData.empty())
                                    .aggregate()
                                    .join()
                                    .headers()
                                    .get(HttpHeaderNames.LOCATION)).isEqualTo("/tokens/forUpdate");

        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH,
                                                         API_V1_PATH_PREFIX + "tokens/forUpdate/level",
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);

        final String body = "{\"level\":\"SYSTEMADMIN\"}";
        final AggregatedHttpResponse response = systemAdminClient.execute(headers, body).aggregate().join();

        final JsonNode jsonNode = Jackson.readTree(response.contentUtf8());
        assertThat(jsonNode.get("appId").asText()).isEqualTo("forUpdate");
        assertThat(jsonNode.get("systemAdmin").asBoolean()).isEqualTo(true);

        final AggregatedHttpResponse response2 = systemAdminClient.execute(headers, body).aggregate().join();
        assertThat(response2.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }
}
