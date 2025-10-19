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

package com.linecorp.centraldogma.server.internal.api.sysadmin;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.StatusCode;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.AbstractService;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;
import com.linecorp.centraldogma.server.internal.api.auth.RequiresSystemAdministrator;
import com.linecorp.centraldogma.server.internal.api.converter.CreateApiResponseConverter;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.Token;
import com.linecorp.centraldogma.server.metadata.User;

/**
 * Annotated service object for managing {@link Token}s.
 */
@ProducesJson
public class TokenService extends AbstractService {

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

    public TokenService(CommandExecutor executor, MetadataService mds) {
        super(executor);
        this.mds = requireNonNull(mds, "mds");
    }

    /**
     * GET /tokens
     *
     * <p>Returns the list of the tokens generated before.
     */
    @Get("/tokens")
    public Collection<Token> listTokens(User loginUser) {
        if (loginUser.isSystemAdmin()) {
            return mds.getTokens().appIds().values();
        } else {
            return mds.getTokens().withoutSecret().appIds().values();
        }
    }

    /**
     * POST /tokens
     *
     * <p>Returns a newly-generated token belonging to the current login user.
     */
    @Post("/tokens")
    @StatusCode(201)
    @ResponseConverter(CreateApiResponseConverter.class)
    public CompletableFuture<ResponseEntity<Token>> createToken(@Param String appId,
                                                                // TODO(minwoox): Remove isAdmin field.
                                                                @Param @Default("false") boolean isAdmin,
                                                                @Param @Default("false") boolean isSystemAdmin,
                                                                @Param @Nullable String secret,
                                                                Author author, User loginUser) {
        final boolean isSystemAdminToken = isSystemAdmin || isAdmin;
        checkArgument(!isSystemAdminToken || loginUser.isSystemAdmin(),
                      "Only system administrators are allowed to create a system admin-level token.");

        checkArgument(secret == null || loginUser.isSystemAdmin(),
                      "Only system administrators are allowed to create a new token from " +
                      " the given secret string");

        final CompletableFuture<Revision> tokenFuture;
        if (secret != null) {
            tokenFuture = mds.createToken(author, appId, secret, isSystemAdminToken);
        } else {
            tokenFuture = mds.createToken(author, appId, isSystemAdminToken);
        }
        return tokenFuture
                .thenCompose(unused -> fetchTokensByAppId(appId))
                .thenApply(token -> {
                    final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.CREATED,
                                                                       HttpHeaderNames.LOCATION,
                                                                       "/tokens/" + appId);
                    return ResponseEntity.of(headers, token);
                });
    }

    /**
     * DELETE /tokens/{appId}
     *
     * <p>Deletes a token of the specified ID then returns it.
     */
    @Delete("/tokens/{appId}")
    public CompletableFuture<Token> deleteToken(ServiceRequestContext ctx,
                                                @Param String appId,
                                                Author author, User loginUser) {
        return getTokenOrRespondForbidden(ctx, appId, loginUser).thenCompose(
                token -> mds.destroyToken(author, appId)
                            .thenApply(unused -> token.withoutSecret()));
    }

    /**
     * DELETE /tokens/{appId}/removed
     *
     * <p>Purges a token of the specified ID that was deleted before.
     */
    @Delete("/tokens/{appId}/removed")
    @RequiresSystemAdministrator
    public CompletableFuture<Token> purgeToken(ServiceRequestContext ctx,
                                               @Param String appId,
                                               Author author, User loginUser) {
        return getTokenOrRespondForbidden(ctx, appId, loginUser).thenApplyAsync(
                token -> {
                    mds.purgeToken(author, appId);
                    return token.withoutSecret();
                }, ctx.blockingTaskExecutor());
    }

    /**
     * PATCH /tokens/{appId}
     *
     * <p>Activates or deactivates the token of the specified {@code appId}.
     */
    @Patch("/tokens/{appId}")
    @Consumes("application/json-patch+json")
    public CompletableFuture<Token> updateToken(ServiceRequestContext ctx,
                                                @Param String appId,
                                                JsonNode node, Author author, User loginUser) {
        return getTokenOrRespondForbidden(ctx, appId, loginUser).thenCompose(
                token -> {
                    if (token.isDeleted()) {
                        throw new IllegalArgumentException(
                                "You can't update the status of the token scheduled for deletion.");
                    }
                    if (node.equals(activation)) {
                        return mds.activateToken(author, appId)
                                  .thenApply(unused -> token.withoutSecret());
                    }
                    if (node.equals(deactivation)) {
                        return mds.deactivateToken(author, appId)
                                  .thenApply(unused -> token.withoutSecret());
                    }
                    throw new IllegalArgumentException("Unsupported JSON patch: " + node +
                                                       " (expected: " + activation + " or " + deactivation +
                                                       ')');
                }
        );
    }

    /**
     * PATCH /tokens/{appId}/level
     *
     * <p>Updates a level of a token of the specified ID.
     */
    @Patch("/tokens/{appId}/level")
    @RequiresSystemAdministrator
    public CompletableFuture<Token> updateTokenLevel(ServiceRequestContext ctx,
                                                     @Param String appId,
                                                     TokenLevelRequest tokenLevelRequest,
                                                     Author author, User loginUser) {

        final String newTokenLevel = tokenLevelRequest.level().toLowerCase();
        checkArgument("user".equals(newTokenLevel) || "admin".equals(newTokenLevel) ||
                      "systemadmin".equals(newTokenLevel),
                      "token level: %s (expected: user or systemadmin)" + tokenLevelRequest.level());

        return getTokenOrRespondForbidden(ctx, appId, loginUser).thenCompose(
                token -> {
                    boolean toBeSystemAdmin = false;

                    switch (newTokenLevel) {
                        case "user":
                            if (!token.isSystemAdmin()) {
                                throw HttpStatusException.of(HttpStatus.NOT_MODIFIED);
                            }
                            break;
                        case "admin":
                        case "systemadmin":
                            if (token.isSystemAdmin()) {
                                throw HttpStatusException.of(HttpStatus.NOT_MODIFIED);
                            }
                            toBeSystemAdmin = true;
                            break;
                    }
                    return mds.updateTokenLevel(author, appId, toBeSystemAdmin).thenCompose(
                            unused -> fetchTokensByAppId(appId).thenApply(Token::withoutSecret));
                });
    }

    private CompletableFuture<Token> fetchTokensByAppId(String appId) {
        return mds.fetchTokens().thenApply(tokens -> tokens.get(appId));
    }

    private CompletableFuture<Token> getTokenOrRespondForbidden(ServiceRequestContext ctx,
                                                                String appId, User loginUser) {
        return fetchTokensByAppId(appId).thenApply(token -> {
            // Give permission to the system administrators.
            if (!loginUser.isSystemAdmin() &&
                !token.creation().user().equals(loginUser.id())) {
                return HttpApiUtil.throwResponse(ctx, HttpStatus.FORBIDDEN,
                                                 "Unauthorized token: %s", token);
            }
            return token;
        });
    }
}
