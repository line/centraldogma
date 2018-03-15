/*
 * Copyright 2018 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.annotation.ConsumeType;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.admin.authentication.User;
import com.linecorp.centraldogma.server.internal.api.converter.CreateApiResponseConverter;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.metadata.HolderWithLocation;
import com.linecorp.centraldogma.server.internal.metadata.MetadataService;
import com.linecorp.centraldogma.server.internal.metadata.Token;
import com.linecorp.centraldogma.server.internal.metadata.Tokens;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;

/**
 * Annotated service object for managing {@link Token}s.
 */
@ExceptionHandler(HttpApiExceptionHandler.class)
public class TokenService extends AbstractService {
    private static final Logger logger = LoggerFactory.getLogger(TokenService.class);

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

    private final MetadataService mds;

    public TokenService(ProjectManager projectManager, CommandExecutor executor,
                        MetadataService mds) {
        super(projectManager, executor);
        this.mds = requireNonNull(mds, "mds");
    }

    /**
     * GET /tokens
     *
     * <p>Returns the list of the tokens generated before.
     */
    @Get("/tokens")
    public CompletableFuture<Collection<Token>> listTokens(@RequestObject User loginUser) {
        if (loginUser.isAdmin()) {
            return mds.getTokens()
                      .thenApply(tokens -> tokens.appIds().values());
        } else {
            return mds.getTokens()
                      .thenApply(Tokens::withoutSecret)
                      .thenApply(tokens -> tokens.appIds().values());
        }
    }

    /**
     * POST /tokens
     *
     * <p>Returns a newly-generated token belonging to the current login user.
     */
    @Post("/tokens")
    @ResponseConverter(CreateApiResponseConverter.class)
    public CompletableFuture<HolderWithLocation<Token>> createToken(@Param("appId") String appId,
                                                                    @Param("isAdmin") boolean isAdmin,
                                                                    @RequestObject Author author,
                                                                    @RequestObject User loginUser) {
        checkArgument(!isAdmin || loginUser.isAdmin(),
                      "Only administrators are allowed to create an admin-level token.");
        return mds.createToken(author, appId, isAdmin)
                  .thenCompose(unused -> mds.findTokenByAppId(appId))
                  .thenApply(token -> HolderWithLocation.of(token, "/tokens/" + appId));
    }

    /**
     * DELETE /tokens/{appId}
     *
     * <p>Deletes a token of the specified ID then returns it.
     */
    @Delete("/tokens/{appId}")
    public CompletableFuture<Token> deleteToken(@Param("appId") String appId,
                                                @RequestObject Author author,
                                                @RequestObject User loginUser) {
        return getTokenOrRespondForbidden(appId, loginUser).thenCompose(
                token -> mds.destroyToken(author, appId)
                            .thenApply(unused -> token.withoutSecret()));
    }

    /**
     * PATCH /tokens/{appId}
     *
     * <p>Activates or deactivates the token of the specified {@code appId}.
     */
    @Patch("/tokens/{appId}")
    @ConsumeType("application/json-patch+json")
    public CompletableFuture<Token> updateToken(@Param("appId") String appId,
                                                @RequestObject JsonNode node,
                                                @RequestObject Author author,
                                                @RequestObject User loginUser) {
        if (node.equals(activation)) {
            return getTokenOrRespondForbidden(appId, loginUser).thenCompose(
                    token -> mds.activateToken(author, appId)
                                .thenApply(unused -> token.withoutSecret()));
        }
        if (node.equals(deactivation)) {
            return getTokenOrRespondForbidden(appId, loginUser).thenCompose(
                    token -> mds.deactivateToken(author, appId)
                                .thenApply(unused -> token.withoutSecret()));
        }
        throw new IllegalArgumentException("Unsupported JSON patch: " + node +
                                           " (expected: " + activation + " or " + deactivation + ')');
    }

    private CompletableFuture<Token> getTokenOrRespondForbidden(String appId,
                                                                User loginUser) {
        return mds.findTokenByAppId(appId).thenApply(token -> {
            // Give permission to the administrators.
            if (!loginUser.isAdmin() &&
                !token.creation().user().equals(loginUser.id())) {
                return Exceptions.throwUnsafely(HttpStatusException.of(HttpStatus.FORBIDDEN));
            }
            return token;
        });
    }
}
