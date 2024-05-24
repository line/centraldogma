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
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.ProjectRole;
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
            builder.administrators(TestAuthMessageUtil.USERNAME);
            builder.authProviderFactory(new TestAuthProviderFactory());
        }
    };

    private static final Author adminAuthor = Author.ofEmail("admin@localhost.com");
    private static final Author guestAuthor = Author.ofEmail("guest@localhost.com");
    private static final User admin = new User("admin@localhost.com", User.LEVEL_ADMIN);
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
    private static WebClient adminClient;

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
        adminClient = WebClient.builder(uri)
                               .auth(AuthToken.ofOAuth2(sessionId(dogma.httpClient(),
                                                                  TestAuthMessageUtil.USERNAME,
                                                                  TestAuthMessageUtil.PASSWORD)))
                               .build();
        metadataService = new MetadataService(manager.projectManager(), manager.executor());
        tokenService = new TokenService(manager.executor(), metadataService);
    }

    @AfterEach
    public void tearDown() {
        final Tokens tokens = metadataService.getTokens().join();
        tokens.appIds().forEach((appId, token) -> {
            if (!token.isDeleted()) {
                metadataService.destroyToken(adminAuthor, appId);
            }
            metadataService.purgeToken(adminAuthor, appId);
        });
    }

    @Test
    void adminToken() {
        final Token token = tokenService.createToken("forAdmin1", true, null, adminAuthor, admin).join()
                                        .content();
        assertThat(token.isActive()).isTrue();
        assertThatThrownBy(() -> tokenService.createToken("forAdmin2", true, null, guestAuthor, guest)
                                             .join())
                .isInstanceOf(IllegalArgumentException.class);

        final StandaloneCommandExecutor executor = (StandaloneCommandExecutor) manager.executor();
        executor.execute(Command.createProject(Author.SYSTEM, "myPro")).join();
        metadataService.addToken(Author.SYSTEM, "myPro", "forAdmin1", ProjectRole.OWNER).join();
        assertThat(metadataService.getProject("myPro").join().tokens().containsKey("forAdmin1")).isTrue();

        final Collection<Token> tokens = tokenService.listTokens(admin).join();
        assertThat(tokens.stream().filter(t -> !StringUtil.isNullOrEmpty(t.secret()))).hasSize(1);

        assertThatThrownBy(() -> tokenService.deleteToken(ctx, "forAdmin1", guestAuthor, guest)
                                             .join())
                .hasCauseInstanceOf(HttpResponseException.class);

        assertThat(tokenService.deleteToken(ctx, "forAdmin1", adminAuthor, admin).thenCompose(
                unused -> tokenService.purgeToken(ctx, "forAdmin1", adminAuthor, admin)).join()).satisfies(
                t -> {
                    assertThat(t.appId()).isEqualTo(token.appId());
                    assertThat(t.isAdmin()).isEqualTo(token.isAdmin());
                    assertThat(t.creation()).isEqualTo(token.creation());
                    assertThat(t.isDeleted()).isTrue();
                });
        assertThat(tokenService.listTokens(admin).join().size()).isEqualTo(0);
        assertThat(metadataService.getProject("myPro").join().tokens().size()).isEqualTo(0);
    }

    @Test
    void userToken() {
        final Token userToken1 = tokenService.createToken("forUser1", false, null, adminAuthor, admin)
                                             .join().content();
        final Token userToken2 = tokenService.createToken("forUser2", false, null, guestAuthor, guest)
                                             .join().content();
        assertThat(userToken1.isActive()).isTrue();
        assertThat(userToken2.isActive()).isTrue();

        final Collection<Token> tokens = tokenService.listTokens(guest).join();
        assertThat(tokens.stream().filter(token -> !StringUtil.isNullOrEmpty(token.secret())).count())
                .isEqualTo(0);

        assertThat(tokenService.deleteToken(ctx, "forUser1", adminAuthor, admin).thenCompose(
                unused -> tokenService.purgeToken(ctx, "forUser1", adminAuthor, admin)).join()).satisfies(t -> {
            assertThat(t.appId()).isEqualTo(userToken1.appId());
            assertThat(t.isAdmin()).isEqualTo(userToken1.isAdmin());
            assertThat(t.creation()).isEqualTo(userToken1.creation());
            assertThat(t.deactivation()).isEqualTo(userToken1.deactivation());
        });
        assertThat(tokenService.deleteToken(ctx, "forUser2", guestAuthor, guest).thenCompose(
                unused -> tokenService.purgeToken(ctx, "forUser2", guestAuthor, guest)).join()).satisfies(t -> {
            assertThat(t.appId()).isEqualTo(userToken2.appId());
            assertThat(t.isAdmin()).isEqualTo(userToken2.isAdmin());
            assertThat(t.creation()).isEqualTo(userToken2.creation());
            assertThat(t.deactivation()).isEqualTo(userToken2.deactivation());
        });
    }

    @Test
    void nonRandomToken() {
        final Token token = tokenService.createToken("forAdmin1", true, "appToken-secret", adminAuthor,
                                                     admin)
                                        .join().content();
        assertThat(token.isActive()).isTrue();

        final Collection<Token> tokens = tokenService.listTokens(admin).join();
        assertThat(tokens.stream().filter(t -> !StringUtil.isNullOrEmpty(t.secret()))).hasSize(1);

        assertThatThrownBy(() -> tokenService.createToken("forUser1", true, "appToken-secret", guestAuthor,
                                                          guest)
                                             .join())
                .isInstanceOf(IllegalArgumentException.class);

        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.DELETE, "/tokens/{appId}/removed"));

        tokenService.deleteToken(this.ctx, "forAdmin1", adminAuthor, admin).thenCompose(
                unused -> tokenService.purgeToken(ctx, "forAdmin1", adminAuthor, admin)).join();
    }

    @Test
    public void updateToken() {
        final Token token = tokenService.createToken("forUpdate", true, null, adminAuthor, admin).join()
                                        .content();
        assertThat(token.isActive()).isTrue();

        tokenService.updateToken(ctx, "forUpdate", deactivation, adminAuthor, admin).join();
        final Token deactivatedToken = metadataService.findTokenByAppId("forUpdate").join();
        assertThat(deactivatedToken.isActive()).isFalse();

        tokenService.updateToken(ctx, "forUpdate", activation, adminAuthor, admin).join();
        final Token activatedToken = metadataService.findTokenByAppId("forUpdate").join();
        assertThat(activatedToken.isActive()).isTrue();

        assertThatThrownBy(
                () -> tokenService.updateToken(ctx, "forUpdate", Jackson.valueToTree(
                        ImmutableList.of(ImmutableMap.of())), adminAuthor, admin).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);

        tokenService.deleteToken(ctx, "forUpdate", adminAuthor, admin).join();
        final Token deletedToken = metadataService.findTokenByAppId("forUpdate").join();
        assertThat(deletedToken.isDeleted()).isTrue();
        assertThatThrownBy(
                () -> tokenService.updateToken(ctx, "forUpdate", activation, adminAuthor, admin).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateTokenLevel() {
        final Token token = tokenService.createToken("forUpdate", false, null, adminAuthor, admin).join()
                                        .content();
        assertThat(token.isActive()).isTrue();

        final Token userToken = tokenService.updateTokenLevel(ctx, "forUpdate", new TokenLevelRequest("ADMIN"),
                                                              adminAuthor, admin)
                                            .join();
        assertThat(userToken.isAdmin()).isTrue();

        final Token adminToken = tokenService.updateTokenLevel(ctx, "forUpdate", new TokenLevelRequest("USER"),
                                                               adminAuthor, admin)
                                             .join();
        assertThat(adminToken.isAdmin()).isFalse();

        assertThatThrownBy(
                () -> tokenService.updateTokenLevel(ctx, "forUpdate", new TokenLevelRequest("INVALID"),
                                                    adminAuthor, admin).join())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createTokenAndUpdateLevel() throws JsonParseException {
        assertThat(adminClient.post(API_V1_PATH_PREFIX + "tokens",
                                    QueryParams.of("appId", "forUpdate", "isAdmin", false),
                                    HttpData.empty())
                              .aggregate()
                              .join()
                              .headers()
                              .get(HttpHeaderNames.LOCATION)).isEqualTo("/tokens/forUpdate");

        final RequestHeaders headers = RequestHeaders.of(HttpMethod.PATCH,
                                                         API_V1_PATH_PREFIX + "tokens/forUpdate/level",
                                                         HttpHeaderNames.CONTENT_TYPE, MediaType.JSON);

        final String body = "{\"level\":\"ADMIN\"}";
        final AggregatedHttpResponse response = adminClient.execute(headers, body).aggregate().join();

        final JsonNode jsonNode = Jackson.readTree(response.contentUtf8());
        assertThat(jsonNode.get("appId").asText()).isEqualTo("forUpdate");
        assertThat(jsonNode.get("admin").asBoolean()).isEqualTo(true);

        final AggregatedHttpResponse response2 = adminClient.execute(headers, body).aggregate().join();
        assertThat(response2.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }
}
