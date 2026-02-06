/*
 * Copyright 2025 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.common.jsonpatch.JsonPatchOperation.asJsonArray;
import static com.linecorp.centraldogma.internal.jsonpatch.JsonPatchUtil.encodeSegment;
import static com.linecorp.centraldogma.server.metadata.AppIdentityRegistry.SECRET_PREFIX;
import static com.linecorp.centraldogma.server.metadata.AppIdentityRegistry.validateSecret;
import static com.linecorp.centraldogma.server.metadata.MetadataService.TOKEN_JSON;
import static com.linecorp.centraldogma.server.metadata.MetadataService.addToMap;
import static com.linecorp.centraldogma.server.metadata.MetadataService.removeFromMap;
import static com.linecorp.centraldogma.server.metadata.MetadataService.updateMap;
import static com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer.INTERNAL_PROJECT_DOGMA;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.ChangeConflictException;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.jsonpatch.JsonPatchOperation;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

final class AppIdentityService {

    private final RepositorySupport<AppIdentityRegistry> appIdentityRegistryRepo;
    private final InternalProjectInitializer projectInitializer;

    AppIdentityService(ProjectManager projectManager, CommandExecutor executor,
                       InternalProjectInitializer projectInitializer) {
        this.projectInitializer = requireNonNull(projectInitializer, "projectInitializer");
        appIdentityRegistryRepo = new RepositorySupport<>(projectManager, executor, AppIdentityRegistry.class);
    }

    CompletableFuture<AppIdentityRegistry> fetchAppIdentityRegistry() {
        return appIdentityRegistryRepo.fetch(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, TOKEN_JSON)
                                      .thenApply(HolderWithRevision::object);
    }

    AppIdentityRegistry getAppIdentityRegistry() {
        return projectInitializer.appIdentityRegistry();
    }

    CompletableFuture<Revision> createToken(Author author, String appId) {
        return createToken(author, appId, false);
    }

    CompletableFuture<Revision> createToken(Author author, String appId, boolean isSystemAdmin) {
        return createToken(author, appId, SECRET_PREFIX + UUID.randomUUID(), isSystemAdmin);
    }

    CompletableFuture<Revision> createToken(Author author, String appId, String secret) {
        return createToken(author, appId, secret, false);
    }

    CompletableFuture<Revision> createToken(Author author, String appId, String secret,
                                            boolean isSystemAdmin) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");
        requireNonNull(secret, "secret");
        validateSecret(secret);

        final Token newToken = new Token(appId, secret, isSystemAdmin, isSystemAdmin,
                                         UserAndTimestamp.of(author));
        final AppIdentityRegistryTransformer transformer = new AppIdentityRegistryTransformer(
                (headRevision, tokens) -> {
                    if (tokens.appIds().containsKey(newToken.id())) {
                        throw new ChangeConflictException("Token already exists: " + newToken.id());
                    }
                    final String newTokenSecret = newToken.secret();
                    assert newTokenSecret != null;
                    if (tokens.secrets().containsKey(newTokenSecret)) {
                        throw new ChangeConflictException("Secret already exists");
                    }

                    final ImmutableMap<String, AppIdentity> newAppIds =
                            ImmutableMap.<String, AppIdentity>builderWithExpectedSize(
                                                tokens.appIds().size() + 1)
                                        .putAll(tokens.appIds())
                                        .put(newToken.id(), newToken)
                                        .build();
                    final ImmutableMap<String, String> newSecrets =
                            ImmutableMap.<String, String>builderWithExpectedSize(tokens.secrets().size() + 1)
                                        .putAll(tokens.secrets())
                                        .put(newTokenSecret, newToken.id())
                                        .build();
                    return new AppIdentityRegistry(newAppIds, newSecrets, tokens.certificateIds());
                });

        return appIdentityRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author,
                                            "Add a token: " + newToken.id(), transformer);
    }

    CompletableFuture<Revision> destroyToken(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final String commitSummary = "Destroy the token: " + appId;
        final UserAndTimestamp userAndTimestamp = UserAndTimestamp.of(author);

        final AppIdentityRegistryTransformer transformer = new AppIdentityRegistryTransformer(
                (headRevision, registry) -> {
                    final AppIdentity appIdentity =
                            getAppIdentityToDestroy(headRevision, registry, appId, AppIdentityType.TOKEN);

                    final Token token = (Token) appIdentity;
                    final String secret = token.secret();
                    assert secret != null;
                    final Token newToken = new Token(
                            appIdentity.appId(), secret,
                            appIdentity.isSystemAdmin(), appIdentity.allowGuestAccess(),
                            appIdentity.creation(), appIdentity.deactivation(), userAndTimestamp);
                    final Map<String, String> newSecrets = removeFromMap(registry.secrets(), secret);
                    return new AppIdentityRegistry(updateMap(registry.appIds(), appId, newToken), newSecrets,
                                                   registry.certificateIds());
                });
        return appIdentityRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA,
                                            author, commitSummary, transformer);
    }

    Revision purgeAppIdentity(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");
        final String commitSummary = "Purge the app identity: " + appId;

        final AppIdentityRegistryTransformer transformer = new AppIdentityRegistryTransformer(
                (headRevision, registry) -> {
                    final AppIdentity appIdentity = registry.get(appId); // Raise an exception if not found.
                    if (appIdentity.deletion() == null) {
                        throw new RedundantChangeException(
                                headRevision, "The app identity must be destroyed before purging: " + appId);
                    }

                    final Map<String, AppIdentity> newAppIds = removeFromMap(registry.appIds(), appId);
                    // The app identity is already removed from secrets and certificateIds when destroyed.
                    return new AppIdentityRegistry(newAppIds, registry.secrets(), registry.certificateIds());
        });
        return appIdentityRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author,
                                            commitSummary, transformer)
                                      .join();
    }

    CompletableFuture<Revision> activateToken(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final String commitSummary = "Activate the token: " + appId;

        final AppIdentityRegistryTransformer transformer = new AppIdentityRegistryTransformer(
                (headRevision, registry) -> {
                    final AppIdentity appIdentity =
                            getAppIdentityToActivate(headRevision, registry, appId, AppIdentityType.TOKEN);
                    final String secret = ((Token) appIdentity).secret();
                    assert secret != null;
                    final Map<String, String> newSecrets =
                            addToMap(registry.secrets(), secret, appId); // The key is secret not appId.
                    final Token newToken = new Token(appIdentity.appId(), secret, appIdentity.isSystemAdmin(),
                                                     appIdentity.allowGuestAccess(), appIdentity.creation());
                    return new AppIdentityRegistry(updateMap(registry.appIds(), appId, newToken), newSecrets,
                                                   registry.certificateIds());
                });
        return appIdentityRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author,
                                            commitSummary, transformer);
    }

    CompletableFuture<Revision> deactivateToken(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final String commitSummary = "Deactivate the token: " + appId;
        final UserAndTimestamp userAndTimestamp = UserAndTimestamp.of(author);

        final AppIdentityRegistryTransformer transformer = new AppIdentityRegistryTransformer(
                (headRevision, registry) -> {
            final AppIdentity appIdentity =
                    getAppIdentityToDeactivate(headRevision, registry, appId, AppIdentityType.TOKEN);
            final String secret = ((Token) appIdentity).secret();
            assert secret != null;
            final Token newToken = new Token(appIdentity.appId(), secret,
                                             appIdentity.isSystemAdmin(), appIdentity.allowGuestAccess(),
                                             appIdentity.creation(), userAndTimestamp, null);
            final Map<String, AppIdentity> newAppIds = updateMap(registry.appIds(), appId, newToken);
            final Map<String, String> newSecrets =
                    removeFromMap(registry.secrets(), secret); // Note that the key is secret not appId.
            return new AppIdentityRegistry(newAppIds, newSecrets, registry.certificateIds());
        });
        return appIdentityRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author,
                                            commitSummary, transformer);
    }

    AppIdentity findAppIdentity(String appId) {
        requireNonNull(appId, "appId");
        return getAppIdentityRegistry().get(appId);
    }

    CertificateAppIdentity findCertificateById(String certificateId) {
        requireNonNull(certificateId, "certificateId");
        return getAppIdentityRegistry().findByCertificateId(certificateId);
    }

    Token findTokenBySecret(String secret) {
        requireNonNull(secret, "secret");
        validateSecret(secret);
        return getAppIdentityRegistry().findBySecret(secret);
    }

    private static AppIdentity getAppIdentityToDestroy(Revision headRevision, AppIdentityRegistry registry,
                                                       String appId, AppIdentityType expectedType) {
        final AppIdentity appIdentity = registry.get(appId); // Raise an exception if not found.
        if (appIdentity.deletion() != null) {
            throw new RedundantChangeException(headRevision, "The app identity is already destroyed: " + appId);
        }
        throwIfInvalidType(appId, appIdentity, expectedType);
        return appIdentity;
    }

    private static AppIdentity getAppIdentityToActivate(Revision headRevision, AppIdentityRegistry registry,
                                                        String appId, AppIdentityType expectedType) {
        final AppIdentity appIdentity = registry.get(appId); // Raise an exception if not found.
        if (appIdentity.deletion() != null) {
            throw new RedundantChangeException(
                    headRevision, "The app identity is already destroyed: " + appId);
        }
        if (appIdentity.deactivation() == null) {
            throw new RedundantChangeException(headRevision, "The app identity is already activated: " + appId);
        }
        throwIfInvalidType(appId, appIdentity, expectedType);
        return appIdentity;
    }

    private static AppIdentity getAppIdentityToDeactivate(Revision headRevision, AppIdentityRegistry registry,
                                                          String appId, AppIdentityType expectedType) {
        final AppIdentity appIdentity = registry.get(appId); // Raise an exception if not found.
        if (appIdentity.deletion() != null) {
            throw new RedundantChangeException(
                    headRevision, "The app identity is already destroyed: " + appId);
        }
        if (appIdentity.deactivation() != null) {
            throw new RedundantChangeException(
                    headRevision, "The app identity is already deactivated: " + appId);
        }
        throwIfInvalidType(appId, appIdentity, expectedType);
        return appIdentity;
    }

    private static void throwIfInvalidType(String appId, AppIdentity appIdentity,
                                           AppIdentityType expectedType) {
        if (appIdentity.type() != expectedType) {
            throw new IllegalArgumentException(
                    appId + " app identity is not a " + expectedType + ": " + appIdentity.type());
        }
    }

    CompletableFuture<Revision> createCertificate(Author author, String appId, String certificateId,
                                                  boolean isSystemAdmin) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");
        checkArgument(!isNullOrEmpty(certificateId), "certificateId must not be null or empty");

        // Does not allow guest access for non admin certificate.
        final CertificateAppIdentity certificate =
                new CertificateAppIdentity(appId, certificateId, isSystemAdmin, isSystemAdmin,
                                           UserAndTimestamp.of(author));
        final JsonPointer appIdPath = JsonPointer.compile("/appIds" + encodeSegment(certificate.appId()));
        final JsonPointer certificateIdPath =
                JsonPointer.compile("/certificateIds" + encodeSegment(certificateId));
        final Change<JsonNode> change =
                Change.ofJsonPatch(TOKEN_JSON,
                                   asJsonArray(JsonPatchOperation.testAbsence(appIdPath),
                                               JsonPatchOperation.testAbsence(certificateIdPath),
                                               JsonPatchOperation.add(appIdPath,
                                                                      Jackson.valueToTree(certificate)),
                                               JsonPatchOperation.add(certificateIdPath,
                                                                      Jackson.valueToTree(
                                                                              certificate.appId()))));
        return appIdentityRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author,
                              "Add a certificate: " + certificate.id(), change);
    }

    CompletableFuture<Revision> destroyCertificate(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final String commitSummary = "Destroy the certificate: " + appId;
        final UserAndTimestamp userAndTimestamp = UserAndTimestamp.of(author);

        final AppIdentityRegistryTransformer transformer = new AppIdentityRegistryTransformer(
                (headRevision, registry) -> {
                    final AppIdentity appIdentity =
                            getAppIdentityToDestroy(headRevision, registry, appId, AppIdentityType.CERTIFICATE);

                    final CertificateAppIdentity newCertificate = new CertificateAppIdentity(
                            appIdentity.appId(), ((CertificateAppIdentity) appIdentity).certificateId(),
                            appIdentity.isSystemAdmin(), appIdentity.allowGuestAccess(),
                            appIdentity.creation(), appIdentity.deactivation(), userAndTimestamp);
                    final String certificateId = ((CertificateAppIdentity) appIdentity).certificateId();
                    final Map<String, String> newCertificateIds =
                            removeFromMap(registry.certificateIds(), certificateId);
                    return new AppIdentityRegistry(updateMap(registry.appIds(), appId, newCertificate),
                                                   registry.secrets(), newCertificateIds);
                });
        return appIdentityRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author, commitSummary,
                                            transformer);
    }

    CompletableFuture<Revision> activateCertificate(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final String commitSummary = "Activate the certificate: " + appId;

        final AppIdentityRegistryTransformer transformer = new AppIdentityRegistryTransformer(
                (headRevision, registry) -> {
                    final AppIdentity appIdentity =
                            getAppIdentityToActivate(headRevision, registry, appId,
                                                     AppIdentityType.CERTIFICATE);
                    final CertificateAppIdentity certificate = (CertificateAppIdentity) appIdentity;
                    final CertificateAppIdentity newCertificate =
                            new CertificateAppIdentity(certificate.appId(),
                                                       certificate.certificateId(),
                                                       certificate.isSystemAdmin(),
                                                       certificate.allowGuestAccess(),
                                                       certificate.creation());
                    final Map<String, String> newCertificateIds =
                            addToMap(registry.certificateIds(), certificate.certificateId(), appId);
                    return new AppIdentityRegistry(updateMap(registry.appIds(), appId, newCertificate),
                                                   registry.secrets(), newCertificateIds);
                });
        return appIdentityRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author,
                                            commitSummary, transformer);
    }

    CompletableFuture<Revision> deactivateCertificate(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final String commitSummary = "Deactivate the certificate: " + appId;
        final UserAndTimestamp userAndTimestamp = UserAndTimestamp.of(author);

        final AppIdentityRegistryTransformer transformer = new AppIdentityRegistryTransformer(
                (headRevision, registry) -> {
                    final AppIdentity appIdentity =
                            getAppIdentityToDeactivate(headRevision, registry, appId,
                                                       AppIdentityType.CERTIFICATE);
                    final String certificateId = ((CertificateAppIdentity) appIdentity).certificateId();
                    final CertificateAppIdentity newCertificate =
                            new CertificateAppIdentity(appIdentity.appId(), certificateId,
                                                       appIdentity.isSystemAdmin(),
                                                       appIdentity.allowGuestAccess(),
                                                       appIdentity.creation(),
                                                       userAndTimestamp, null);
                    final Map<String, AppIdentity> newAppIds = updateMap(registry.appIds(), appId,
                                                                         newCertificate);
                    final Map<String, String> newCertificateIds =
                            removeFromMap(registry.certificateIds(), certificateId);
                    return new AppIdentityRegistry(newAppIds, registry.secrets(), newCertificateIds);
                });
        return appIdentityRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author,
                                            commitSummary, transformer);
    }

    CompletableFuture<Revision> updateAppIdentityLevel(Author author, String appId,
                                                       boolean toBeSystemAdmin) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");
        final String commitSummary = "Update the app identity level: " + appId + " to " +
                                     (toBeSystemAdmin ? "system admin" : "user");
        final AppIdentityRegistryTransformer transformer = new AppIdentityRegistryTransformer(
                (headRevision, registry) -> {
                    final AppIdentity appIdentity = registry.get(appId); // Raise an exception if not found.
                    if (toBeSystemAdmin == appIdentity.isSystemAdmin()) {
                        throw new RedundantChangeException(
                                headRevision,
                                "The app identity is already " + (toBeSystemAdmin ? "system admin" : "user"));
                    }

                    final AppIdentity newAppIdentity = appIdentity.withSystemAdmin(toBeSystemAdmin);
                    return new AppIdentityRegistry(updateMap(registry.appIds(), appId, newAppIdentity),
                                                   registry.secrets(), registry.certificateIds());
                });
        return appIdentityRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author, commitSummary,
                                            transformer);
    }
}
