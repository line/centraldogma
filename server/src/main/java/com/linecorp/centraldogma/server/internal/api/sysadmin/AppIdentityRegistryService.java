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
import static com.google.common.collect.ImmutableList.toImmutableList;
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
import com.linecorp.armeria.server.annotation.ConsumesJson;
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
import com.linecorp.centraldogma.server.metadata.AppIdentity;
import com.linecorp.centraldogma.server.metadata.AppIdentityType;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.Token;
import com.linecorp.centraldogma.server.metadata.User;

/**
 * Annotated service object for managing {@link AppIdentity}s.
 */
@ProducesJson
public final class AppIdentityRegistryService extends AbstractService {

    private static final JsonNode LEGACY_ACTIVATION = Jackson.valueToTree(
            ImmutableList.of(
                    ImmutableMap.of("op", "replace",
                                    "path", "/status",
                                    "value", "active")));
    private static final JsonNode LEGACY_DEACTIVATION = Jackson.valueToTree(
            ImmutableList.of(
                    ImmutableMap.of("op", "replace",
                                    "path", "/status",
                                    "value", "inactive")));

    private final MetadataService mds;
    private final boolean mtlsEnabled;

    public AppIdentityRegistryService(CommandExecutor executor, MetadataService mds, boolean mtlsEnabled) {
        super(executor);
        this.mds = requireNonNull(mds, "mds");
        this.mtlsEnabled = mtlsEnabled;
    }

    /**
     * GET /appIdentities
     *
     * <p>Returns the list of the app identities.
     */
    @Get("/appIdentities")
    public Collection<AppIdentity> listAppIdentities(User loginUser) {
        if (loginUser.isSystemAdmin()) {
            return mds.getAppIdentityRegistry().appIds().values();
        } else {
            return mds.getAppIdentityRegistry().withoutSecret().appIds().values();
        }
    }

    /**
     * POST /appIdentities
     *
     * <p>Returns a newly-generated app identity belonging to the current login user.
     */
    @Post("/appIdentities")
    @StatusCode(201)
    @ResponseConverter(CreateApiResponseConverter.class)
    public CompletableFuture<ResponseEntity<AppIdentity>> createAppIdentity(
            @Param String appId,
            @Param @Default("false") boolean isSystemAdmin,
            @Param AppIdentityType appIdentityType,
            @Param @Nullable String secret,
            @Param @Nullable String certificateId,
            Author author, User loginUser) {
        if (!mtlsEnabled && appIdentityType == AppIdentityType.CERTIFICATE) {
            throw new IllegalArgumentException(
                    "Cannot create a CERTIFICATE type app identity when mTLS is disabled.");
        }

        if (!loginUser.isSystemAdmin()) {
            checkArgument(
                    !isSystemAdmin,
                    "Only system administrators are allowed to create a system admin-level app identity.");
            checkArgument(
                    secret == null,
                    "Only system administrators are allowed to create a new application token from " +
                    "the given secret string");
        }
        final CompletableFuture<Revision> future;
        if (appIdentityType == AppIdentityType.TOKEN) {
            checkArgument(certificateId == null,
                          "TOKEN type cannot have a certificateId: %s", certificateId);
            if (secret != null) {
                future = mds.createToken(author, appId, secret, isSystemAdmin);
            } else {
                future = mds.createToken(author, appId, isSystemAdmin);
            }
        } else {
            checkArgument(certificateId != null, "CERTIFICATE type must have a certificateId.");
            checkArgument(secret == null,
                          "CERTIFICATE type cannot have a secret: %s", secret);
            future = mds.createCertificate(author, appId, certificateId, isSystemAdmin);
        }
        return future.thenCompose(unused -> fetchAppIdentity(appId))
                     .thenApply(appIdentity -> {
                         final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.CREATED,
                                                                            HttpHeaderNames.LOCATION,
                                                                            "/appIdentities/" + appId);
                         return ResponseEntity.of(headers, appIdentity);
                     });
    }

    /**
     * DELETE /appIdentities/{appId}
     *
     * <p>Deletes an app identity of the specified ID then returns it.
     */
    @Delete("/appIdentities/{appId}")
    public CompletableFuture<AppIdentity> deleteAppIdentity(ServiceRequestContext ctx,
                                                            @Param String appId,
                                                            Author author, User loginUser) {
        return getAppIdentityOrRespondForbidden(ctx, appId, loginUser).thenCompose(
                appIdentities -> {
                    if (appIdentities.type() == AppIdentityType.TOKEN) {
                        return mds.destroyToken(author, appId)
                                  .thenApply(unused -> ((Token) appIdentities).withoutSecret());
                    }
                    return mds.destroyCertificate(author, appId).thenApply(unused -> appIdentities);
                });
    }

    /**
     * DELETE /appIdentities/{appId}/removed
     *
     * <p>Purges an app identity of the specified ID that was deleted before.
     */
    @Delete("/appIdentities/{appId}/removed")
    @RequiresSystemAdministrator
    public CompletableFuture<AppIdentity> purgeAppIdentity(ServiceRequestContext ctx,
                                                           @Param String appId,
                                                           Author author, User loginUser) {
        return getAppIdentityOrRespondForbidden(ctx, appId, loginUser).thenApplyAsync(
                appIdentity -> {
                    mds.purgeAppIdentity(author, appId);
                    if (appIdentity.type() == AppIdentityType.TOKEN) {
                        return ((Token) appIdentity).withoutSecret();
                    }
                    return appIdentity;
                }, ctx.blockingTaskExecutor());
    }

    /**
     * PATCH /appIdentities/{appId}
     *
     * <p>Activates or deactivates the app identity of the specified {@code appId}.
     */
    @Patch("/appIdentities/{appId}")
    @ConsumesJson
    public CompletableFuture<AppIdentity> updateAppIdentity(ServiceRequestContext ctx,
                                                            @Param String appId,
                                                            JsonNode node, Author author, User loginUser) {
        // {"status":"active"} or {"status":"inactive"}
        final JsonNode status = node.get("status");
        if (status == null || status.isNull() || !status.isTextual()) {
            throw new IllegalArgumentException(
                    "The request must contain a 'status' field with a string value.");
        }
        final String text = status.asText();
        if (!"active".equals(text) && !"inactive".equals(text)) {
            throw new IllegalArgumentException(
                    "The 'status' field must be either 'active' or 'inactive': " + text);
        }

        return getAppIdentityOrRespondForbidden(ctx, appId, loginUser).thenCompose(
                appIdentity -> {
                    if (appIdentity.isDeleted()) {
                        throw new IllegalArgumentException(
                                "You can't update the status of the app identity scheduled for deletion.");
                    }
                    if ("active".equals(text)) {
                        if (appIdentity.type() == AppIdentityType.TOKEN) {
                            return mds.activateToken(author, appId);
                        } else {
                            return mds.activateCertificate(author, appId);
                        }
                    }
                    if (appIdentity.type() == AppIdentityType.TOKEN) {
                        return mds.deactivateToken(author, appId);
                    } else {
                        return mds.deactivateCertificate(author, appId);
                    }
                }
        ).thenCompose(unused -> {
            // Fetch the updated app identity.
            return fetchAppIdentity(appId).thenApply(updated -> {
                if (updated.type() == AppIdentityType.TOKEN) {
                    return ((Token) updated).withoutSecret();
                }
                return updated;
            });
        });
    }

    /**
     * PATCH /appIdentities/{appId}/level
     *
     * <p>Updates a level of the app identity of the specified ID.
     */
    @Patch("/appIdentities/{appId}/level")
    @ConsumesJson
    @RequiresSystemAdministrator
    public CompletableFuture<AppIdentity> updateAppIdentityLevel(
            ServiceRequestContext ctx,
            @Param String appId,
            AppIdentityLevelRequest appIdentityLevelRequest,
            Author author, User loginUser) {
        final String newAppIdentityLevel = appIdentityLevelRequest.level().toLowerCase();
        checkArgument("user".equals(newAppIdentityLevel) || "systemadmin".equals(newAppIdentityLevel),
                      "app identity level: %s (expected: user or systemadmin)",
                      appIdentityLevelRequest.level());

        return getAppIdentityOrRespondForbidden(ctx, appId, loginUser).thenCompose(
                appIdentity -> {
                    boolean toBeSystemAdmin = false;

                    switch (newAppIdentityLevel) {
                        case "user":
                            if (!appIdentity.isSystemAdmin()) {
                                throw HttpStatusException.of(HttpStatus.NOT_MODIFIED);
                            }
                            break;
                        case "systemadmin":
                            if (appIdentity.isSystemAdmin()) {
                                throw HttpStatusException.of(HttpStatus.NOT_MODIFIED);
                            }
                            toBeSystemAdmin = true;
                            break;
                    }
                    return mds.updateAppIdentityLevel(author, appId, toBeSystemAdmin).thenCompose(
                            unused -> fetchAppIdentity(appId).thenApply(updated -> {
                                if (updated.type() == AppIdentityType.TOKEN) {
                                    return ((Token) updated).withoutSecret();
                                }
                                return updated;
                            }));
                });
    }

    /**
     * GET /tokens
     *
     * <p>Returns the list of the tokens generated before.
     *
     * @deprecated Use {@link #listAppIdentities(User)} instead.
     */
    @Get("/tokens")
    @Deprecated
    public Collection<Token> listTokens(User loginUser) {
        if (loginUser.isSystemAdmin()) {
            return mds.getAppIdentityRegistry()
                      .appIds()
                      .values()
                      .stream()
                      .filter(appIdentity -> appIdentity.type() == AppIdentityType.TOKEN)
                      .map(appIdentity -> (Token) appIdentity)
                      .collect(toImmutableList());
        } else {
            return mds.getAppIdentityRegistry()
                      .appIds()
                      .values()
                      .stream()
                      .filter(appIdentity -> appIdentity.type() == AppIdentityType.TOKEN)
                      .map(appIdentity -> (Token) appIdentity)
                      .map(Token::withoutSecret)
                      .collect(toImmutableList());
        }
    }

    /**
     * POST /tokens
     *
     * <p>Returns a newly-generated token belonging to the current login user.
     *
     * @deprecated Use {@link #createAppIdentity(
     *             String, boolean, AppIdentityType, String, String, Author, User)}.
     */
    @Post("/tokens")
    @StatusCode(201)
    @ResponseConverter(CreateApiResponseConverter.class)
    @Deprecated
    public CompletableFuture<ResponseEntity<Token>> createToken(@Param String appId,
                                                                @Param @Default("false") boolean isSystemAdmin,
                                                                @Param @Nullable String secret,
                                                                Author author, User loginUser) {
        return createAppIdentity(appId, isSystemAdmin, AppIdentityType.TOKEN, secret, null,
                                 author, loginUser)
                .thenApply(responseEntity -> {
                    final AppIdentity app = responseEntity.content();
                    return ResponseEntity.of(responseEntity.headers(), (Token) app);
                });
    }

    /**
     * DELETE /tokens/{appId}
     *
     * <p>Deletes a token of the specified ID then returns it.
     *
     * @deprecated Use {@link #deleteAppIdentity(ServiceRequestContext, String, Author, User)}.
     */
    @Delete("/tokens/{appId}")
    @Deprecated
    public CompletableFuture<Token> deleteToken(ServiceRequestContext ctx,
                                                @Param String appId,
                                                Author author, User loginUser) {
        return getTokenOrRespondForbidden(ctx, appId, loginUser).thenCompose(
                token -> {
                    return mds.destroyToken(author, appId)
                              .thenApply(unused -> token.withoutSecret());
                });
    }

    /**
     * DELETE /tokens/{appId}/removed
     *
     * <p>Purges a token of the specified ID that was deleted before.
     *
     * @deprecated Use {@link #purgeAppIdentity(ServiceRequestContext, String, Author, User)}.
     */
    @Delete("/tokens/{appId}/removed")
    @RequiresSystemAdministrator
    @Deprecated
    public CompletableFuture<Token> purgeToken(ServiceRequestContext ctx,
                                               @Param String appId,
                                               Author author, User loginUser) {
        return getTokenOrRespondForbidden(ctx, appId, loginUser).thenApplyAsync(
                token -> {
                    mds.purgeAppIdentity(author, appId);
                    return token.withoutSecret();
                }, ctx.blockingTaskExecutor());
    }

    /**
     * PATCH /tokens/{appId}
     *
     * <p>Activates or deactivates the token of the specified {@code appId}.
     *
     * @deprecated Use {@link #updateAppIdentity(ServiceRequestContext, String, JsonNode, Author, User)}.
     */
    @Patch("/tokens/{appId}")
    @Consumes("application/json-patch+json")
    @Deprecated
    public CompletableFuture<Token> updateToken(ServiceRequestContext ctx,
                                                @Param String appId,
                                                JsonNode node, Author author, User loginUser) {
        return getTokenOrRespondForbidden(ctx, appId, loginUser).thenCompose(
                token -> {
                    if (token.isDeleted()) {
                        throw new IllegalArgumentException(
                                "You can't update the status of the token scheduled for deletion.");
                    }
                    if (node.equals(LEGACY_ACTIVATION)) {
                        return mds.activateToken(author, appId)
                                  .thenApply(unused -> token.withoutSecret());
                    }
                    if (node.equals(LEGACY_DEACTIVATION)) {
                        return mds.deactivateToken(author, appId)
                                  .thenApply(unused -> token.withoutSecret());
                    }
                    throw new IllegalArgumentException("Unsupported JSON patch: " + node +
                                                       " (expected: " + LEGACY_ACTIVATION + " or " +
                                                       LEGACY_DEACTIVATION + ')');
                }
        );
    }

    /**
     * PATCH /tokens/{appId}/level
     *
     * <p>Updates a level of a token of the specified ID.
     *
     * @deprecated Use {@link #updateAppIdentityLevel(ServiceRequestContext, String,
     *             AppIdentityLevelRequest, Author, User)}.
     */
    @Patch("/tokens/{appId}/level")
    @RequiresSystemAdministrator
    @Deprecated
    public CompletableFuture<Token> updateTokenLevel(ServiceRequestContext ctx,
                                                     @Param String appId,
                                                     AppIdentityLevelRequest appIdentityLevelRequest,
                                                     Author author, User loginUser) {
        // Call getTokenOrRespondForbidden first to check if it is a token.
        return getTokenOrRespondForbidden(ctx, appId, loginUser)
                .thenCompose(unused -> updateAppIdentityLevel(
                        ctx, appId, appIdentityLevelRequest, author, loginUser)
                        .thenApply(appIdentity -> (Token) appIdentity));
    }

    private CompletableFuture<AppIdentity> fetchAppIdentity(String appId) {
        return mds.fetchAppIdentityRegistry().thenApply(registry -> registry.get(appId));
    }

    private CompletableFuture<AppIdentity> getAppIdentityOrRespondForbidden(ServiceRequestContext ctx,
                                                                            String appId, User loginUser) {
        return fetchAppIdentity(appId).thenApply(appIdentity -> {
            // Give permission to the system administrators.
            if (!loginUser.isSystemAdmin() &&
                !appIdentity.creation().user().equals(loginUser.id())) {
                return HttpApiUtil.throwResponse(ctx, HttpStatus.FORBIDDEN,
                                                 "Do not have permission for app identity: %s",
                                                 appIdentity.appId());
            }
            return appIdentity;
        });
    }

    private CompletableFuture<Token> getTokenOrRespondForbidden(ServiceRequestContext ctx,
                                                                String appId, User loginUser) {
        return getAppIdentityOrRespondForbidden(ctx, appId, loginUser).thenApply(appIdentity -> {
            if (appIdentity.type() != AppIdentityType.TOKEN) {
                return HttpApiUtil.throwResponse(
                        ctx, HttpStatus.NOT_FOUND, "%s is not a token but a %s",
                        appIdentity.appId(), appIdentity.type());
            }

            return (Token) appIdentity;
        });
    }
}
