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

package com.linecorp.centraldogma.server.internal.admin.service;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.centraldogma.internal.Util.validateFileName;
import static com.linecorp.centraldogma.server.internal.admin.authentication.Token.EMPTY_TOKEN;
import static com.linecorp.centraldogma.server.internal.admin.service.RepositoryUtil.push;
import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.INTERNAL_PROJECT_NAME;
import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.TOKEN_REPOSITORY_NAME;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.admin.authentication.AuthenticationUtil;
import com.linecorp.centraldogma.server.internal.admin.authentication.Token;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.httpapi.AbstractService;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;

/**
 * Annotated service object for managing application tokens.
 */
public class TokenService extends AbstractService {

    private static final String TOKEN_JSON_PATH = "/token.json";
    private static final String SECRET_PREFIX = "appToken-";

    private static final TypeReference<Map<String, Token>>
            TOKEN_MAP_TYPE_REFERENCE = new TypeReference<Map<String, Token>>() {};

    public TokenService(ProjectManager projectManager, CommandExecutor executor) {
        super(projectManager, executor);
    }

    /**
     * GET /tokens
     * Returns the list of the tokens generated before.
     */
    @Get("/tokens")
    public CompletionStage<Collection<Token>> listTokens() {
        return getTokens(Revision.HEAD)
                .thenApply(Map::values)
                .thenApply(tokens -> {
                    final List<Token> withoutSecrets = new ArrayList<>();
                    tokens.forEach(token -> withoutSecrets.add(token.withoutSecret()));
                    return Collections.unmodifiableList(withoutSecrets);
                });
    }

    /**
     * POST /tokens
     * Returns a newly-generated token belonging to the current login user.
     */
    @Post("/tokens")
    public CompletionStage<Token> createToken(@Param("appId") String appId) {
        validateFileName(appId, "appId");
        return projectManager()
                .get(INTERNAL_PROJECT_NAME).repos().get(TOKEN_REPOSITORY_NAME)
                .normalize(Revision.HEAD)
                .thenCompose(revision -> {
                    final Token token =
                            new Token(appId, SECRET_PREFIX + UUID.randomUUID(),
                                      AuthenticationUtil.currentUser(), new Date());
                    return createToken0(revision, token);
                });
    }

    private CompletionStage<Token> createToken0(Revision revision, Token newToken) {
        return getTokens(revision).thenCompose(entries -> {
            final boolean exist = entries.values().stream().anyMatch(
                    token -> token.appId().equals(newToken.appId()));
            if (exist) {
                // TODO(hyangtack) Would send 409 conflict when the following PR is merged.
                // https://github.com/line/armeria/pull/746
                throw HttpStatusException.of(HttpStatus.CONFLICT);
            }

            entries.put(newToken.secret(), newToken);
            final User user = AuthenticationUtil.currentUser();
            final String summary = user.name() + " generates a token: " + newToken.appId();
            return updateTokens(revision, entries, summary).thenApply(unused -> newToken);
        });
    }

    /**
     * DELETE /tokens/{id}
     * Deletes a token of the specified ID then returns it.
     */
    @Delete("/tokens/{id}")
    public CompletionStage<Token> deleteToken(@Param("id") String id) {
        return projectManager()
                .get(INTERNAL_PROJECT_NAME).repos().get(TOKEN_REPOSITORY_NAME)
                .normalize(Revision.HEAD)
                .thenCompose(revision -> deleteToken0(revision, id));
    }

    private CompletionStage<Token> deleteToken0(Revision revision, String id) {
        return getTokens(revision).thenCompose(entries -> {
            final Optional<Token> target = entries.values().stream()
                                                  .filter(v -> id.equals(v.appId()))
                                                  .findAny();
            if (target.isPresent()) {
                final Token token = target.get();
                entries.remove(token.secret());
                final User user = AuthenticationUtil.currentUser();
                final String summary = user.name() + " deletes a token: " + token.appId();
                return updateTokens(revision, entries, summary).thenApply(unused -> token);
            } else {
                return CompletableFuture.completedFuture(EMPTY_TOKEN);
            }
        });
    }

    /**
     * Finds a token of the specified secret.
     */
    public CompletionStage<Token> findToken(String secret) {
        checkArgument(secret != null && secret.startsWith(SECRET_PREFIX),
                      "Secret should start with " + SECRET_PREFIX + "'");
        return projectManager()
                .get(INTERNAL_PROJECT_NAME).repos().get(TOKEN_REPOSITORY_NAME)
                .get(Revision.HEAD, Query.ofJsonPath(TOKEN_JSON_PATH, "$." + secret))
                .thenApply(result -> {
                    if (result.isRemoved() ||
                        result.type() != EntryType.JSON) {
                        return null;
                    }
                    return Jackson.convertValue(result.content(), Token.class);
                });
    }

    private CompletionStage<Map<String, Token>> getTokens(Revision revision) {
        final CompletableFuture<Map<String, Token>> future = new CompletableFuture<>();
        projectManager()
                .get(INTERNAL_PROJECT_NAME).repos().get(TOKEN_REPOSITORY_NAME)
                .get(revision, TOKEN_JSON_PATH)
                .thenAccept(entry -> future.complete(convert(entry)))
                .exceptionally(cause -> {
                    future.complete(Maps.newHashMap());
                    return null;
                });
        return future;
    }

    private CompletionStage<?> updateTokens(Revision revision, Map<String, Token> tokens,
                                            String summary) {
        return push(this, INTERNAL_PROJECT_NAME, TOKEN_REPOSITORY_NAME, revision,
                    AuthenticationUtil.currentAuthor(), summary, "",
                    Markup.PLAINTEXT, Change.ofJsonUpsert(TOKEN_JSON_PATH, Jackson.valueToTree(tokens)));
    }

    private static Map<String, Token> convert(Entry<?> entry) {
        if (entry.type() == EntryType.JSON) {
            return Jackson.convertValue(entry.content(), TOKEN_MAP_TYPE_REFERENCE);
        } else {
            return Maps.newHashMap();
        }
    }
}
