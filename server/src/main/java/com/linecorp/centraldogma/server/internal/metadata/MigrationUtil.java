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

package com.linecorp.centraldogma.server.internal.metadata;

import static com.linecorp.centraldogma.server.internal.command.Command.createRepository;
import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.INTERNAL_PROJ;
import static com.linecorp.centraldogma.server.internal.command.ProjectInitializer.INTERNAL_REPO;
import static com.linecorp.centraldogma.server.internal.metadata.MetadataService.METADATA_JSON;
import static com.linecorp.centraldogma.server.internal.metadata.MetadataService.TOKEN_JSON;
import static com.linecorp.centraldogma.server.internal.metadata.RepositoryUtil.convertWithJackson;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.RedundantChangeException;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.admin.authentication.LegacyToken;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.internal.storage.project.SafeProjectManager;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

/**
 * A utility class for upgrading Central Dogma servers.
 */
public final class MigrationUtil {
    private static final Logger logger = LoggerFactory.getLogger(MigrationUtil.class);

    /**
     * The repository name for legacy tokens.
     */
    static final String LEGACY_TOKEN_REPO = "tokens";

    /**
     * The file name of legacy tokens.
     */
    static final String LEGACY_TOKEN_JSON = "/token.json";

    /**
     * A type reference for reading legacy "token.json".
     */
    private static final TypeReference<Map<String, LegacyToken>>
            TOKEN_MAP_TYPE_REFERENCE = new TypeReference<Map<String, LegacyToken>>() {};

    /**
     * A default author doing migration.
     */
    private static final Author author = Author.SYSTEM;

    /**
     * Migrates tokens as a new format and creates a metadata file if it does not exist in the existing project.
     * This method assumes that every replica runs under read-only mode while upgrading them and restarting
     * them one by one. Also, it is not necessary to be executed asynchronously because it has to be called
     * before binding RPC server, so every change is pushed synchronously.
     */
    public static synchronized void migrate(ProjectManager projectManager, CommandExecutor executor) {
        requireNonNull(projectManager, "projectManager");
        requireNonNull(executor, "executor");

        final RepositoryUtil<ProjectMetadata> metadataRepo =
                new RepositoryUtil<>(projectManager, executor,
                                     entry -> convertWithJackson(entry, ProjectMetadata.class));

        final UserAndTimestamp userAndTimestamp = UserAndTimestamp.of(author);

        // Check whether tokens are migrated or not.
        final Entry<?> tokenEntry = projectManager.get(INTERNAL_PROJ).repos()
                                                  .get(INTERNAL_REPO)
                                                  .getOrNull(Revision.HEAD, TOKEN_JSON).join();
        final Collection<Token> migratedTokens =
                tokenEntry == null ? migrateTokens(projectManager, executor) : ImmutableSet.of();
        migratedTokens.forEach(token -> logger.info("Token '{}' has been migrated", token.id()));

        // Create a metadata.json file if it does not exist in the meta repository of the project.
        // Use SafeProjectManager in order not to migrate internal projects.
        final SafeProjectManager safeProjectManager = new SafeProjectManager(projectManager);

        // Check whether "${project}/meta/metadata.json" exists or not in case of upgrading from 0.23.0.
        safeProjectManager.list().values().forEach(p -> {
            if (alreadyMigrated(p)) {
                return;
            }
            if (!p.repos().exists(Project.REPO_META)) {
                return;
            }
            try {
                final ProjectMetadata metadata =
                        metadataRepo.fetch(p.name(), Project.REPO_META, METADATA_JSON).join().object();
                // "meta" repository is changed as a user repository now.
                metadata.repos().put(Project.REPO_META,
                                     new RepositoryMetadata(Project.REPO_META, userAndTimestamp,
                                                            PerRolePermissions.ofPublic()));
                commitProjectMetadata(metadataRepo, executor, p.name(), metadata);

                metadataRepo.push(p.name(), Project.REPO_META, author,
                                  "Remove the old metadata file", Change.ofRemoval(METADATA_JSON)).join();
            } catch (Throwable cause) {
                cause = Exceptions.peel(cause);
                if (!(cause instanceof EntryNotFoundException)) {
                    Exceptions.throwUnsafely(cause);
                }
            }
        });

        // Create a token registration map for metadata migration.
        final UserAndTimestamp creationTime = UserAndTimestamp.of(author);
        final Map<String, TokenRegistration> registrations =
                migratedTokens.stream()
                              .map(t -> new TokenRegistration(t.id(), ProjectRole.MEMBER, creationTime))
                              .collect(toMap(TokenRegistration::id, Function.identity()));

        safeProjectManager.list().values().forEach(p -> {
            if (alreadyMigrated(p)) {
                return;
            }
            final Map<String, RepositoryMetadata> repos =
                    p.repos().list().values().stream()
                     .filter(r -> !r.name().equals(INTERNAL_REPO))
                     .map(r -> new RepositoryMetadata(r.name(), userAndTimestamp,
                                                      PerRolePermissions.ofPublic()))
                     .collect(toMap(RepositoryMetadata::name, Function.identity()));

            final ProjectMetadata metadata = new ProjectMetadata(p.name(), repos, ImmutableMap.of(),
                                                                 registrations, userAndTimestamp, null);
            commitProjectMetadata(metadataRepo, executor, p.name(), metadata);
        });
    }

    private static boolean alreadyMigrated(Project project) {
        try {
            final Repository internalRepo = project.repos().get(INTERNAL_REPO);
            return internalRepo != null &&
                   internalRepo.getOrNull(Revision.HEAD, METADATA_JSON) != null;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static void commitProjectMetadata(RepositoryUtil<ProjectMetadata> metadataRepo,
                                              CommandExecutor executor, String projectName,
                                              ProjectMetadata metadata) {
        try {
            executor.execute(createRepository(author, projectName, INTERNAL_REPO)).join();
        } catch (Throwable cause) {
            cause = Exceptions.peel(cause);
            if (!(cause instanceof RepositoryExistsException)) {
                Exceptions.throwUnsafely(cause);
            }
        }
        try {
            metadataRepo.push(projectName, INTERNAL_REPO, author,
                              "Add the metadata file",
                              Change.ofJsonUpsert(METADATA_JSON, Jackson.valueToTree(metadata)))
                        .toCompletableFuture().join();
            logger.info("Project '{}' has been migrated", projectName);
        } catch (Throwable cause) {
            cause = Exceptions.peel(cause);
            if (!(cause instanceof RedundantChangeException)) {
                Exceptions.throwUnsafely(cause);
            }
        }
    }

    private static Collection<Token> migrateTokens(ProjectManager projectManager,
                                                   CommandExecutor executor) {

        final RepositoryUtil<Tokens> tokensRepo =
                new RepositoryUtil<>(projectManager, executor,
                                     entry -> convertWithJackson(entry, Tokens.class));

        final Project project = projectManager.get(INTERNAL_PROJ);

        Runnable successCallback = null;
        Tokens tokens = null;
        // Check whether "dogma/main/tokens.json" exists or not in case of upgrading from 0.23.0.
        // The difference between 0.23.0 and now is only the repository name.
        if (project.repos().exists("main")) {
            try {
                tokens = tokensRepo.fetch(INTERNAL_PROJ, "main", TOKEN_JSON)
                                   .thenApply(HolderWithRevision::object)
                                   .join();
                successCallback = () ->
                        tokensRepo.push(INTERNAL_PROJ, "main", author,
                                        "Remove the old token list file",
                                        Change.ofRemoval(TOKEN_JSON)).join();
            } catch (Throwable cause) {
                cause = Exceptions.peel(cause);
                if (!(cause instanceof EntryNotFoundException)) {
                    Exceptions.throwUnsafely(cause);
                }
            }
        }

        if (tokens == null) {
            Collection<LegacyToken> legacyTokens = null;
            if (project.repos().exists(LEGACY_TOKEN_REPO)) {
                // Legacy tokens are stored in "dogma/tokens/token.json".
                try {
                    legacyTokens = project.repos().get(LEGACY_TOKEN_REPO)
                                          .getOrNull(Revision.HEAD, LEGACY_TOKEN_JSON)
                                          .thenApply(entry -> {
                                              if (entry != null) {
                                                  return Jackson.<Map<String, LegacyToken>>convertValue(
                                                          entry.content(), TOKEN_MAP_TYPE_REFERENCE).values();
                                              } else {
                                                  return ImmutableList.<LegacyToken>of();
                                              }
                                          }).join();
                    successCallback = () ->
                            tokensRepo.push(INTERNAL_PROJ, LEGACY_TOKEN_REPO, author,
                                            "Remove the old token list file",
                                            Change.ofRemoval(LEGACY_TOKEN_JSON)).join();
                } catch (Throwable cause) {
                    cause = Exceptions.peel(cause);
                    if (!(cause instanceof EntryNotFoundException)) {
                        Exceptions.throwUnsafely(cause);
                    }
                }
            }
            if (legacyTokens == null) {
                legacyTokens = ImmutableList.of();
            }

            // Read legacy tokens then make a new Tokens instance for the current Central Dogma.
            final Map<String, Token> tokenMap = legacyTokens.stream().map(MigrationUtil::migrateToken)
                                                            .collect(toMap(Token::id, Function.identity()));
            final Map<String, String> secretMap = tokenMap.values().stream()
                                                          .collect(toMap(Token::secret, Token::id));
            tokens = new Tokens(tokenMap, secretMap);
        }

        boolean success = false;
        try {
            final Change<?> change = Change.ofJsonUpsert(TOKEN_JSON, Jackson.valueToTree(tokens));
            tokensRepo.push(INTERNAL_PROJ, INTERNAL_REPO, author,
                            "Add the token list file", change).toCompletableFuture().join();
            success = true;
        } catch (Throwable cause) {
            cause = Exceptions.peel(cause);
            if (!(cause instanceof RedundantChangeException)) {
                Exceptions.throwUnsafely(cause);
            }
        }
        try {
            if (success && successCallback != null) {
                successCallback.run();
            }
        } catch (Throwable ignore) {
            // It is okay not to remove old files. Just ignore the exception.
        }
        return tokens.appIds().values();
    }

    private static Token migrateToken(LegacyToken legacyToken) {
        return new Token(legacyToken.appId(), legacyToken.secret(), false,
                         new UserAndTimestamp(legacyToken.creator().email(), legacyToken.creationTime()));
    }

    private MigrationUtil() {}
}
