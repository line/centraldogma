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
import static com.linecorp.centraldogma.server.metadata.ApplicationRegistry.SECRET_PREFIX;
import static com.linecorp.centraldogma.server.metadata.ApplicationRegistry.validateSecret;
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

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.jsonpatch.JsonPatchOperation;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

final class ApplicationService {

    /**
     * A path of token list file.
     */
    // TODO(minwoox): Rename to /application-registry.json
    private static final String TOKEN_JSON = "/tokens.json";

    private final RepositorySupport<ApplicationRegistry> applicationRegistryRepo;
    private final InternalProjectInitializer projectInitializer;

    ApplicationService(ProjectManager projectManager, CommandExecutor executor,
                       InternalProjectInitializer projectInitializer) {
        this.projectInitializer = requireNonNull(projectInitializer, "projectInitializer");
        applicationRegistryRepo = new RepositorySupport<>(projectManager, executor, ApplicationRegistry.class);
    }

    CompletableFuture<ApplicationRegistry> fetchApplicationRegistry() {
        return applicationRegistryRepo.fetch(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, TOKEN_JSON)
                                      .thenApply(HolderWithRevision::object);
    }

    ApplicationRegistry getApplicationRegistry() {
        return projectInitializer.applicationRegistry();
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
        final JsonPointer appIdPath = JsonPointer.compile("/appIds" + encodeSegment(newToken.id()));
        final String newTokenSecret = newToken.secret();
        assert newTokenSecret != null;
        final JsonPointer secretPath = JsonPointer.compile("/secrets" + encodeSegment(newTokenSecret));
        final Change<JsonNode> change =
                Change.ofJsonPatch(TOKEN_JSON,
                                   asJsonArray(JsonPatchOperation.testAbsence(appIdPath),
                                               JsonPatchOperation.testAbsence(secretPath),
                                               JsonPatchOperation.add(appIdPath, Jackson.valueToTree(newToken)),
                                               JsonPatchOperation.add(secretPath,
                                                                      Jackson.valueToTree(newToken.id()))));
        return applicationRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author,
                                            "Add a token: " + newToken.id(), change);
    }

    CompletableFuture<Revision> destroyToken(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final String commitSummary = "Destroy the token: " + appId;
        final UserAndTimestamp userAndTimestamp = UserAndTimestamp.of(author);

        final ApplicationRegistryTransformer transformer = new ApplicationRegistryTransformer(
                (headRevision, registry) -> {
                    final Application application =
                            getApplicationToDestroy(headRevision, registry, appId, ApplicationType.TOKEN);

                    final Token token = (Token) application;
                    final String secret = token.secret();
                    assert secret != null;
                    final Token newToken = new Token(
                            application.appId(), secret,
                            application.isSystemAdmin(), application.allowGuestAccess(),
                            application.creation(), application.deactivation(), userAndTimestamp);
                    final Map<String, String> newSecrets = removeFromMap(registry.secrets(), secret);
                    return new ApplicationRegistry(updateMap(registry.appIds(), appId, newToken), newSecrets,
                                                   registry.certificateIds());
                });
        return applicationRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA,
                                            author, commitSummary, transformer);
    }

    Revision purgeApplication(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");
        final String commitSummary = "Purge the application: " + appId;

        final ApplicationRegistryTransformer transformer = new ApplicationRegistryTransformer(
                (headRevision, registry) -> {
                    final Application application = registry.get(appId); // Raise an exception if not found.
                    if (application.deletion() == null) {
                        throw new RedundantChangeException(
                                headRevision, "The application must be destroyed before purging: " + appId);
                    }

                    final Map<String, Application> newAppIds = removeFromMap(registry.appIds(), appId);
                    // The application is already removed from secrets and certificateIds when destroyed.
                    return new ApplicationRegistry(newAppIds, registry.secrets(), registry.certificateIds());
        });
        return applicationRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author,
                                            commitSummary, transformer)
                                      .join();
    }

    CompletableFuture<Revision> activateToken(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final String commitSummary = "Activate the token: " + appId;

        final ApplicationRegistryTransformer transformer = new ApplicationRegistryTransformer(
                (headRevision, registry) -> {
                    final Application application =
                            getApplicationToActivate(headRevision, registry, appId, ApplicationType.TOKEN);
                    final String secret = ((Token) application).secret();
                    assert secret != null;
                    final Map<String, String> newSecrets =
                            addToMap(registry.secrets(), secret, appId); // The key is secret not appId.
                    final Token newToken = new Token(application.appId(), secret, application.isSystemAdmin(),
                                                     application.allowGuestAccess(), application.creation());
                    return new ApplicationRegistry(updateMap(registry.appIds(), appId, newToken), newSecrets,
                                                   registry.certificateIds());
                });
        return applicationRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author,
                                            commitSummary, transformer);
    }

    CompletableFuture<Revision> deactivateToken(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final String commitSummary = "Deactivate the token: " + appId;
        final UserAndTimestamp userAndTimestamp = UserAndTimestamp.of(author);

        final ApplicationRegistryTransformer transformer = new ApplicationRegistryTransformer(
                (headRevision, registry) -> {
            final Application application =
                    getApplicationToDeactivate(headRevision, registry, appId, ApplicationType.TOKEN);
            final String secret = ((Token) application).secret();
            assert secret != null;
            final Token newToken = new Token(application.appId(), secret,
                                             application.isSystemAdmin(), application.allowGuestAccess(),
                                             application.creation(), userAndTimestamp, null);
            final Map<String, Application> newAppIds = updateMap(registry.appIds(), appId, newToken);
            final Map<String, String> newSecrets =
                    removeFromMap(registry.secrets(), secret); // Note that the key is secret not appId.
            return new ApplicationRegistry(newAppIds, newSecrets, registry.certificateIds());
        });
        return applicationRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author,
                                            commitSummary, transformer);
    }

    Application findApplicationByAppId(String appId) {
        requireNonNull(appId, "appId");
        return getApplicationRegistry().get(appId);
    }

    ApplicationCertificate findCertificateById(String certificateId) {
        requireNonNull(certificateId, "certificateId");
        return getApplicationRegistry().findByCertificateId(certificateId);
    }

    Token findTokenBySecret(String secret) {
        requireNonNull(secret, "secret");
        validateSecret(secret);
        return getApplicationRegistry().findBySecret(secret);
    }

    private static Application getApplicationToDestroy(Revision headRevision, ApplicationRegistry registry,
                                                       String appId, ApplicationType expectedType) {
        final Application application = registry.get(appId); // Raise an exception if not found.
        if (application.deletion() != null) {
            throw new RedundantChangeException(headRevision, "The application is already destroyed: " + appId);
        }
        throwIfInvalidType(appId, application, expectedType);
        return application;
    }

    private static Application getApplicationToActivate(Revision headRevision, ApplicationRegistry registry,
                                                        String appId, ApplicationType expectedType) {
        final Application application = registry.get(appId); // Raise an exception if not found.
        if (application.deletion() != null) {
            throw new RedundantChangeException(
                    headRevision, "The application is already destroyed: " + appId);
        }
        if (application.deactivation() == null) {
            throw new RedundantChangeException(headRevision, "The application is already activated: " + appId);
        }
        throwIfInvalidType(appId, application, expectedType);
        return application;
    }

    private static Application getApplicationToDeactivate(Revision headRevision, ApplicationRegistry registry,
                                                          String appId, ApplicationType expectedType) {
        final Application application = registry.get(appId); // Raise an exception if not found.
        if (application.deletion() != null) {
            throw new RedundantChangeException(
                    headRevision, "The application is already destroyed: " + appId);
        }
        if (application.deactivation() != null) {
            throw new RedundantChangeException(
                    headRevision, "The application is already deactivated: " + appId);
        }
        throwIfInvalidType(appId, application, expectedType);
        return application;
    }

    private static void throwIfInvalidType(String appId, Application application,
                                           ApplicationType expectedType) {
        if (application.type() != expectedType) {
            throw new IllegalArgumentException(
                    appId + " application is not a " + expectedType + ": " + application.type());
        }
    }

    CompletableFuture<Revision> createCertificate(Author author, String appId, String certificateId,
                                                  boolean isSystemAdmin) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");
        checkArgument(!isNullOrEmpty(certificateId), "certificateId must not be null or empty");

        // Does not allow guest access for non admin certificate.
        final ApplicationCertificate certificate =
                new ApplicationCertificate(appId, certificateId, isSystemAdmin, isSystemAdmin,
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
        return applicationRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author,
                              "Add a certificate: " + certificate.id(), change);
    }

    CompletableFuture<Revision> destroyCertificate(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final String commitSummary = "Destroy the certificate: " + appId;
        final UserAndTimestamp userAndTimestamp = UserAndTimestamp.of(author);

        final ApplicationRegistryTransformer transformer = new ApplicationRegistryTransformer(
                (headRevision, registry) -> {
                    final Application application =
                            getApplicationToDestroy(headRevision, registry, appId, ApplicationType.CERTIFICATE);

                    final ApplicationCertificate newCertificate = new ApplicationCertificate(
                            application.appId(), ((ApplicationCertificate) application).certificateId(),
                            application.isSystemAdmin(), application.allowGuestAccess(),
                            application.creation(), application.deactivation(), userAndTimestamp);
                    final String certificateId = ((ApplicationCertificate) application).certificateId();
                    final Map<String, String> newCertificateIds =
                            removeFromMap(registry.certificateIds(), certificateId);
                    return new ApplicationRegistry(updateMap(registry.appIds(), appId, newCertificate),
                                                   registry.secrets(), newCertificateIds);
                });
        return applicationRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author, commitSummary,
                                            transformer);
    }

    CompletableFuture<Revision> activateCertificate(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final String commitSummary = "Activate the certificate: " + appId;

        final ApplicationRegistryTransformer transformer = new ApplicationRegistryTransformer(
                (headRevision, registry) -> {
                    final Application application =
                            getApplicationToActivate(headRevision, registry, appId,
                                                     ApplicationType.CERTIFICATE);
                    final ApplicationCertificate certificate = (ApplicationCertificate) application;
                    final ApplicationCertificate newCertificate =
                            new ApplicationCertificate(certificate.appId(),
                                                       certificate.certificateId(),
                                                       certificate.isSystemAdmin(),
                                                       certificate.allowGuestAccess(),
                                                       certificate.creation());
                    final Map<String, String> newCertificateIds =
                            addToMap(registry.certificateIds(), certificate.certificateId(), appId);
                    return new ApplicationRegistry(updateMap(registry.appIds(), appId, newCertificate),
                                                   registry.secrets(), newCertificateIds);
                });
        return applicationRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author,
                                            commitSummary, transformer);
    }

    CompletableFuture<Revision> deactivateCertificate(Author author, String appId) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");

        final String commitSummary = "Deactivate the certificate: " + appId;
        final UserAndTimestamp userAndTimestamp = UserAndTimestamp.of(author);

        final ApplicationRegistryTransformer transformer = new ApplicationRegistryTransformer(
                (headRevision, registry) -> {
                    final Application application =
                            getApplicationToDeactivate(headRevision, registry, appId,
                                                       ApplicationType.CERTIFICATE);
                    final String certificateId = ((ApplicationCertificate) application).certificateId();
                    final ApplicationCertificate newCertificate =
                            new ApplicationCertificate(application.appId(), certificateId,
                                                       application.isSystemAdmin(),
                                                       application.allowGuestAccess(),
                                                       application.creation(),
                                                       userAndTimestamp, null);
                    final Map<String, Application> newAppIds = updateMap(registry.appIds(), appId,
                                                                         newCertificate);
                    final Map<String, String> newCertificateIds =
                            removeFromMap(registry.certificateIds(), certificateId);
                    return new ApplicationRegistry(newAppIds, registry.secrets(), newCertificateIds);
                });
        return applicationRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author,
                                            commitSummary, transformer);
    }

    CompletableFuture<Revision> updateApplicationLevel(Author author, String appId,
                                                       boolean toBeSystemAdmin) {
        requireNonNull(author, "author");
        requireNonNull(appId, "appId");
        final String commitSummary =
                "Update the application level: " + appId + " to " + (toBeSystemAdmin ? "system admin" : "user");
        final ApplicationRegistryTransformer transformer = new ApplicationRegistryTransformer(
                (headRevision, registry) -> {
                    final Application application = registry.get(appId); // Raise an exception if not found.
                    if (toBeSystemAdmin == application.isSystemAdmin()) {
                        throw new RedundantChangeException(
                                headRevision,
                                "The application is already " + (toBeSystemAdmin ? "system admin" : "user"));
                    }

                    final Application newApplication = application.withSystemAdmin(toBeSystemAdmin);
                    return new ApplicationRegistry(updateMap(registry.appIds(), appId, newApplication),
                                                   registry.secrets(), registry.certificateIds());
                });
        return applicationRegistryRepo.push(INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA, author, commitSummary,
                                            transformer);
    }
}
