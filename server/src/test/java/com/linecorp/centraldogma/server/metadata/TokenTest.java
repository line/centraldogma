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
package com.linecorp.centraldogma.server.metadata;

import static com.linecorp.centraldogma.common.jsonpatch.JsonPatchOperation.asJsonArray;
import static com.linecorp.centraldogma.server.metadata.MetadataService.TOKEN_JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Collection;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.jsonpatch.JsonPatchOperation;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.api.sysadmin.TokenLevelRequest;
import com.linecorp.centraldogma.server.internal.api.sysadmin.TokenService;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class TokenTest {

    @RegisterExtension
    static final ProjectManagerExtension manager = new ProjectManagerExtension();

    private static final String APP_ID = "foo-id";
    private static final String APP_SECRET = "appToken-foo";
    private static final Author AUTHOR = Author.ofEmail("systemAdmin@localhost.com");
    private static final User USER = new User("systemAdmin@localhost.com", User.LEVEL_SYSTEM_ADMIN);

    private static final ServiceRequestContext CTX =
            ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();

    private static TokenService tokenService;
    private static MetadataService metadataService;

    @BeforeAll
    static void setUp() throws JsonParseException {
        metadataService = new MetadataService(manager.projectManager(), manager.executor(),
                                              manager.internalProjectInitializer());
        tokenService = new TokenService(manager.executor(), metadataService);

        // Put the legacy token.
        final Repository dogmaRepository =
                manager.projectManager().get(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA).repos()
                       .get(Project.REPO_DOGMA);
        final JsonPointer appIdPath = JsonPointer.compile("/appIds/" + APP_ID);
        final JsonPointer secretPath = JsonPointer.compile("/secrets/" + APP_SECRET);
        final Change<JsonNode> change = Change.ofJsonPatch(
                TOKEN_JSON,
                asJsonArray(JsonPatchOperation.testAbsence(appIdPath),
                            JsonPatchOperation.testAbsence(secretPath),
                            JsonPatchOperation.add(appIdPath, Jackson.readTree(tokenJson(true))),
                            JsonPatchOperation.add(secretPath, Jackson.valueToTree(APP_ID))));

        dogmaRepository.commit(Revision.HEAD, System.currentTimeMillis(), AUTHOR,
                               "Add the legacy token", change).join();
    }

    @Test
    void deserializeToken() throws Exception {
        final String legacyTokenJson = tokenJson(true);
        final Token legacyToken = Jackson.readValue(legacyTokenJson, Token.class);
        assertThat(legacyToken.appId()).isEqualTo(APP_ID);
        assertThat(legacyToken.isSystemAdmin()).isTrue();

        final String tokenJson = tokenJson(false);
        final Token token = Jackson.readValue(tokenJson, Token.class);
        assertThat(token.appId()).isEqualTo(APP_ID);
        assertThat(token.isSystemAdmin()).isTrue();
    }

    private static String tokenJson(boolean legacy) {
        return "{\"appId\": \"" + APP_ID + "\"," +
               "  \"secret\": \"" + APP_SECRET + "\"," +
               (legacy ? "  \"admin\": true," : " \"systemAdmin\": true,") +
               "  \"creation\": {" +
               "    \"user\": \"foo@foo.com\"," +
               "    \"timestamp\": \"2018-04-10T09:58:20.032Z\"" +
               "}}";
    }

    @Test
    void updateToken() throws JsonParseException {
        final Collection<Token> tokens = tokenService.listTokens(USER);
        assertThat(tokens.size()).isOne();
        final Token token = Iterables.getFirst(tokens, null);
        assertThat(token.appId()).isEqualTo(APP_ID);
        assertThat(token.isSystemAdmin()).isTrue();
        assertThat(token.isActive()).isTrue();

        final JsonNode deactivation = Jackson.valueToTree(
                ImmutableList.of(
                        ImmutableMap.of("op", "replace",
                                        "path", "/status",
                                        "value", "inactive")));

        tokenService.updateToken(CTX, APP_ID, deactivation, AUTHOR, USER).join();
        await().untilAsserted(() -> assertThat(metadataService.findTokenByAppId(APP_ID).isActive()).isFalse());
        Token updated = metadataService.findTokenByAppId(APP_ID);
        assertThat(updated.appId()).isEqualTo(APP_ID);
        assertThat(updated.isSystemAdmin()).isTrue();

        final JsonNode activation = Jackson.valueToTree(
                ImmutableList.of(
                        ImmutableMap.of("op", "replace",
                                        "path", "/status",
                                        "value", "active")));

        tokenService.updateToken(CTX, APP_ID, activation, AUTHOR, USER).join();
        await().untilAsserted(() -> assertThat(metadataService.findTokenByAppId(APP_ID).isActive()).isTrue());
        updated = metadataService.findTokenByAppId(APP_ID);
        assertThat(updated.appId()).isEqualTo(APP_ID);
        assertThat(updated.isSystemAdmin()).isTrue();
    }

    @Test
    void updateTokenLevel() {
        final Token userToken =
                tokenService.updateTokenLevel(CTX, APP_ID, new TokenLevelRequest("USER"), AUTHOR, USER)
                            .join();
        assertThat(userToken.isSystemAdmin()).isFalse();

        final Token updatedToken =
                tokenService.updateTokenLevel(CTX, APP_ID, new TokenLevelRequest("SYSTEMADMIN"),
                                              AUTHOR, USER).join();
        assertThat(updatedToken.isSystemAdmin()).isTrue();
    }
}
