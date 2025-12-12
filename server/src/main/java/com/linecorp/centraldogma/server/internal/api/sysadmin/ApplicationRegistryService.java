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
import com.linecorp.centraldogma.server.metadata.Application;
import com.linecorp.centraldogma.server.metadata.ApplicationType;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.Token;
import com.linecorp.centraldogma.server.metadata.User;

/**
 * Annotated service object for managing {@link Application}s.
 */
@ProducesJson
public final class ApplicationRegistryService extends AbstractService {

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

    public ApplicationRegistryService(CommandExecutor executor, MetadataService mds, boolean mtlsEnabled) {
        super(executor);
        this.mds = requireNonNull(mds, "mds");
        this.mtlsEnabled = mtlsEnabled;
    }

    /**
     * GET /applications
     *
     * <p>Returns the list of the applications.
     */
    @Get("/applications")
    public Collection<Application> listApplications(User loginUser) {
        if (loginUser.isSystemAdmin()) {
            return mds.getApplicationRegistry().appIds().values();
        } else {
            return mds.getApplicationRegistry().withoutSecret().appIds().values();
        }
    }

    /**
     * POST /applications
     *
     * <p>Returns a newly-generated application belonging to the current login user.
     */
    @Post("/applications")
    @StatusCode(201)
    @ResponseConverter(CreateApiResponseConverter.class)
    public CompletableFuture<ResponseEntity<Application>> createApplication(
            @Param String appId,
            @Param @Default("false") boolean isSystemAdmin,
            @Param ApplicationType applicationType,
            @Param @Nullable String secret,
            @Param @Nullable String certificateId,
            Author author, User loginUser) {
        if (!mtlsEnabled && applicationType == ApplicationType.CERTIFICATE) {
            throw new IllegalArgumentException(
                    "Cannot create a CERTIFICATE type application when mTLS is disabled.");
        }

        if (!loginUser.isSystemAdmin()) {
            checkArgument(!isSystemAdmin,
                          "Only system administrators are allowed to create a system admin-level application.");
            checkArgument(secret == null,
                          "Only system administrators are allowed to create a new application token from " +
                          "the given secret string");
        }
        final CompletableFuture<Revision> future;
        if (applicationType == ApplicationType.TOKEN) {
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
        return future.thenCompose(unused -> fetchApplicationByAppId(appId))
                     .thenApply(application -> {
                         final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.CREATED,
                                                                            HttpHeaderNames.LOCATION,
                                                                            "/applications/" + appId);
                         return ResponseEntity.of(headers, application);
                     });
    }

    /**
     * DELETE /applications/{appId}
     *
     * <p>Deletes an application of the specified ID then returns it.
     */
    @Delete("/applications/{appId}")
    public CompletableFuture<Application> deleteApplication(ServiceRequestContext ctx,
                                                            @Param String appId,
                                                            Author author, User loginUser) {
        return getApplicationOrRespondForbidden(ctx, appId, loginUser).thenCompose(
                application -> {
                    if (application.type() == ApplicationType.TOKEN) {
                        return mds.destroyToken(author, appId)
                                  .thenApply(unused -> ((Token) application).withoutSecret());
                    }
                    return mds.destroyCertificate(author, appId).thenApply(unused -> application);
                });
    }

    /**
     * DELETE /applications/{appId}/removed
     *
     * <p>Purges an application of the specified ID that was deleted before.
     */
    @Delete("/applications/{appId}/removed")
    @RequiresSystemAdministrator
    public CompletableFuture<Application> purgeApplication(ServiceRequestContext ctx,
                                                           @Param String appId,
                                                           Author author, User loginUser) {
        return getApplicationOrRespondForbidden(ctx, appId, loginUser).thenApplyAsync(
                application -> {
                    mds.purgeApplication(author, appId);
                    if (application.type() == ApplicationType.TOKEN) {
                        return ((Token) application).withoutSecret();
                    }
                    return application;
                }, ctx.blockingTaskExecutor());
    }

    /**
     * PATCH /applications/{appId}
     *
     * <p>Activates or deactivates the application of the specified {@code appId}.
     */
    @Patch("/applications/{appId}")
    @Consumes("application/json-patch+json")
    public CompletableFuture<Application> updateApplication(ServiceRequestContext ctx,
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

        return getApplicationOrRespondForbidden(ctx, appId, loginUser).thenCompose(
                application -> {
                    if (application.isDeleted()) {
                        throw new IllegalArgumentException(
                                "You can't update the status of the application scheduled for deletion.");
                    }
                    if ("active".equals(text)) {
                        if (application.type() == ApplicationType.TOKEN) {
                            return mds.activateToken(author, appId)
                                      .thenApply(unused -> ((Token) application).withoutSecret());
                        } else {
                            return mds.activateCertificate(author, appId)
                                      .thenApply(unused -> application);
                        }
                    }
                    if (application.type() == ApplicationType.TOKEN) {
                        return mds.deactivateToken(author, appId)
                                  .thenApply(unused -> ((Token) application).withoutSecret());
                    } else {
                        return mds.deactivateCertificate(author, appId)
                                  .thenApply(unused -> application);
                    }
                }
        );
    }

    /**
     * PATCH /applications/{appId}/level
     *
     * <p>Updates a level of the application of the specified ID.
     */
    @Patch("/applications/{appId}/level")
    @RequiresSystemAdministrator
    public CompletableFuture<Application> updateApplicationLevel(
            ServiceRequestContext ctx,
            @Param String appId,
            ApplicationLevelRequest applicationLevelRequest,
            Author author, User loginUser) {
        final String newTokenLevel = applicationLevelRequest.level().toLowerCase();
        checkArgument("user".equals(newTokenLevel) || "systemadmin".equals(newTokenLevel),
                      "token level: %s (expected: user or systemadmin)", applicationLevelRequest.level());

        return getApplicationOrRespondForbidden(ctx, appId, loginUser).thenCompose(
                application -> {
                    boolean toBeSystemAdmin = false;

                    switch (newTokenLevel) {
                        case "user":
                            if (!application.isSystemAdmin()) {
                                throw HttpStatusException.of(HttpStatus.NOT_MODIFIED);
                            }
                            break;
                        case "systemadmin":
                            if (application.isSystemAdmin()) {
                                throw HttpStatusException.of(HttpStatus.NOT_MODIFIED);
                            }
                            toBeSystemAdmin = true;
                            break;
                    }
                    return mds.updateApplicationLevel(author, appId, toBeSystemAdmin).thenCompose(
                            unused -> fetchApplicationByAppId(appId).thenApply(updated -> {
                                if (updated.type() == ApplicationType.TOKEN) {
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
     * @deprecated Use {@link #listApplications(User)} instead.
     */
    @Get("/tokens")
    @Deprecated
    public Collection<Token> listTokens(User loginUser) {
        if (loginUser.isSystemAdmin()) {
            return mds.getApplicationRegistry()
                      .appIds()
                      .values()
                      .stream()
                      .filter(application -> application.type() == ApplicationType.TOKEN)
                      .map(application -> (Token) application)
                      .collect(toImmutableList());
        } else {
            return mds.getApplicationRegistry()
                      .appIds()
                      .values()
                      .stream()
                      .filter(application -> application.type() == ApplicationType.TOKEN)
                      .map(application -> (Token) application)
                      .map(Token::withoutSecret)
                      .collect(toImmutableList());
        }
    }

    /**
     * POST /tokens
     *
     * <p>Returns a newly-generated token belonging to the current login user.
     *
     * @deprecated Use {@link #createApplication(
     *             String, boolean, ApplicationType, String, String, Author, User)}.
     */
    @Post("/tokens")
    @StatusCode(201)
    @ResponseConverter(CreateApiResponseConverter.class)
    @Deprecated
    public CompletableFuture<ResponseEntity<Token>> createToken(@Param String appId,
                                                                @Param @Default("false") boolean isSystemAdmin,
                                                                @Param @Nullable String secret,
                                                                Author author, User loginUser) {
        return createApplication(appId, isSystemAdmin, ApplicationType.TOKEN, secret, null,
                                 author, loginUser)
                .thenApply(responseEntity -> {
                    final Application app = responseEntity.content();
                    return ResponseEntity.of(responseEntity.headers(), (Token) app);
                });
    }

    /**
     * DELETE /tokens/{appId}
     *
     * <p>Deletes a token of the specified ID then returns it.
     *
     * @deprecated Use {@link #deleteApplication(ServiceRequestContext, String, Author, User)}.
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
     * @deprecated Use {@link #purgeApplication(ServiceRequestContext, String, Author, User)}.
     */
    @Delete("/tokens/{appId}/removed")
    @RequiresSystemAdministrator
    @Deprecated
    public CompletableFuture<Token> purgeToken(ServiceRequestContext ctx,
                                               @Param String appId,
                                               Author author, User loginUser) {
        return getTokenOrRespondForbidden(ctx, appId, loginUser).thenApplyAsync(
                token -> {
                    mds.purgeApplication(author, appId);
                    return token.withoutSecret();
                }, ctx.blockingTaskExecutor());
    }

    /**
     * PATCH /tokens/{appId}
     *
     * <p>Activates or deactivates the token of the specified {@code appId}.
     *
     * @deprecated Use {@link #updateApplication(ServiceRequestContext, String, JsonNode, Author, User)}.
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
     * @deprecated Use {@link #updateApplicationLevel(ServiceRequestContext, String,
     *             ApplicationLevelRequest, Author, User)}.
     */
    @Patch("/tokens/{appId}/level")
    @RequiresSystemAdministrator
    @Deprecated
    public CompletableFuture<Token> updateTokenLevel(ServiceRequestContext ctx,
                                                     @Param String appId,
                                                     ApplicationLevelRequest applicationLevelRequest,
                                                     Author author, User loginUser) {
        // Call getTokenOrRespondForbidden first to check if it is a token.
        return getTokenOrRespondForbidden(ctx, appId, loginUser)
                .thenCompose(unused -> updateApplicationLevel(
                        ctx, appId, applicationLevelRequest, author, loginUser)
                        .thenApply(application -> ((Token) application).withoutSecret()));
    }

    private CompletableFuture<Application> fetchApplicationByAppId(String appId) {
        return mds.fetchApplicationRegistry().thenApply(registry -> registry.get(appId));
    }

    private CompletableFuture<Application> getApplicationOrRespondForbidden(ServiceRequestContext ctx,
                                                                            String appId, User loginUser) {
        return fetchApplicationByAppId(appId).thenApply(application -> {
            // Give permission to the system administrators.
            if (!loginUser.isSystemAdmin() &&
                !application.creation().user().equals(loginUser.id())) {
                return HttpApiUtil.throwResponse(ctx, HttpStatus.FORBIDDEN,
                                                 "Do not have permission for application: %s",
                                                 application.appId());
            }
            return application;
        });
    }

    private CompletableFuture<Token> getTokenOrRespondForbidden(ServiceRequestContext ctx,
                                                                String appId, User loginUser) {
        return getApplicationOrRespondForbidden(ctx, appId, loginUser).thenApply(application -> {
            if (application.type() != ApplicationType.TOKEN) {
                return HttpApiUtil.throwResponse(
                        ctx, HttpStatus.NOT_FOUND, "%s is not a token but a %s",
                        application.appId(), application.type());
            }

            return (Token) application;
        });
    }
}
