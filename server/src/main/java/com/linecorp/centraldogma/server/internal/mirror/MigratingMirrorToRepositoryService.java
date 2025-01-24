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
package com.linecorp.centraldogma.server.internal.mirror;

import static com.linecorp.centraldogma.internal.CredentialUtil.credentialFile;
import static com.linecorp.centraldogma.internal.CredentialUtil.credentialName;
import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.CREDENTIALS;
import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.LEGACY_MIRRORS_PATH;
import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.mirrorFile;
import static com.linecorp.centraldogma.server.storage.project.Project.REPO_META;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.credential.LegacyCredential;
import com.linecorp.centraldogma.server.internal.storage.repository.MirrorConfig;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class MigratingMirrorToRepositoryService {

    private static final Logger logger = LoggerFactory.getLogger(MigratingMirrorToRepositoryService.class);

    @VisibleForTesting
    static final String MIRROR_TO_REPOSITORY_MIGRATION_JOB_LOG = "/mirror-to-repository-migration-job.json";

    private final ProjectManager projectManager;
    private final CommandExecutor commandExecutor;

    MigratingMirrorToRepositoryService(ProjectManager projectManager, CommandExecutor commandExecutor) {
        this.projectManager = projectManager;
        this.commandExecutor = commandExecutor;
    }

    void migrate() throws Exception {
        if (hasMigrationLog()) {
            logger.debug("Mirrors have already been migrated to each repository. Skipping auto migration...");
            return;
        }

        final Stopwatch stopwatch = Stopwatch.createStarted();
        int numCredentialMigratedProjects = 0;
        int numMirrorMigratedProjects = 0;
        String projectName = "";
        try {
            for (Project project : projectManager.list().values()) {
                projectName = project.name();
                logger.info("Migrating credentials in the project: {} ...", projectName);
                final MetaRepository repository = project.metaRepo();
                if (migrateCredentials(projectName, repository)) {
                    numCredentialMigratedProjects++;
                    logger.info("Credentials in the project '{}' have been migrated.", projectName);
                } else {
                    logger.info("No legacy configurations of credentials found in the project: {}.",
                                projectName);
                }

                logger.info("Migrating mirrors in the project: {} ...", projectName);
                if (migrateMirrors(repository)) {
                    numMirrorMigratedProjects++;
                    logger.info("Mirrors in the project '{}' have been migrated.", projectName);
                } else {
                    logger.info("No legacy configurations of mirrors found in the project: {}.",
                                projectName);
                }
            }
            logMigrationJob(numCredentialMigratedProjects, numMirrorMigratedProjects);
        } catch (Exception ex) {
            logger.warn("Failed to migrate credentials and mirrors. project: {}", projectName, ex);
            return;
        }
        logger.info("Mirrors to each repository migration has been completed. (took: {} ms.) " +
                    "migrated credentials: {}, migrated mirrors: {}",
                    stopwatch.elapsed().toMillis(), numCredentialMigratedProjects, numMirrorMigratedProjects);
    }

    private void logMigrationJob(int numCredentialMigratedProjects,
                                 int numMirrorMigratedProjects) throws Exception {
        final Map<String, Object> data =
                ImmutableMap.of("timestamp", Instant.now(),
                                "credentials", numCredentialMigratedProjects,
                                "mirrors", numMirrorMigratedProjects);
        final Change<JsonNode> change = Change.ofJsonUpsert(MIRROR_TO_REPOSITORY_MIGRATION_JOB_LOG,
                                                            Jackson.writeValueAsString(data));
        final Command<CommitResult> command =
                Command.push(Author.SYSTEM, InternalProjectInitializer.INTERNAL_PROJECT_DOGMA,
                             Project.REPO_DOGMA, Revision.HEAD,
                             "Migration of mirrors to each repository has been done.", "",
                             Markup.PLAINTEXT, change);
        executeCommand(command);
    }

    private boolean hasMigrationLog() throws Exception {
        final Project internalProj = projectManager.get(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA);
        final Repository repository = internalProj.repos().get(Project.REPO_DOGMA);
        final Map<String, Entry<?>> entries = repository.find(Revision.HEAD,
                                                              MIRROR_TO_REPOSITORY_MIGRATION_JOB_LOG).get();
        final Entry<?> entry = entries.get(MIRROR_TO_REPOSITORY_MIGRATION_JOB_LOG);
        return entry != null;
    }

    private boolean migrateCredentials(String projectName, MetaRepository repository) throws Exception {
        final Map<String, Entry<?>> entries = repository.find(Revision.HEAD, CREDENTIALS + "*.json")
                                                        .join();
        if (entries.isEmpty()) {
            return false;
        }

        final List<Change<?>> changes = new ArrayList<>();
        for (Map.Entry<String, Entry<?>> entry : entries.entrySet()) {
            final JsonNode content = (JsonNode) entry.getValue().content();
            if (!content.isObject()) {
                warnInvalidConfig(entry, "credential", content);
                continue;
            }
            try {
                final LegacyCredential legacyCredential = Jackson.treeToValue(content, LegacyCredential.class);
                // Migrate the credential to a project level one.
                final String name = credentialName(projectName, legacyCredential.id());
                final Credential newCredential = legacyCredential.toNewCredential(name);
                final String file = credentialFile(name);
                assert entry.getKey().equals(file);
                changes.add(Change.ofJsonUpsert(file, Jackson.valueToTree(newCredential)));
            } catch (JsonProcessingException e) {
                warnInvalidConfig(entry, "credential", content);
            }
        }
        if (changes.isEmpty()) {
            return false;
        }

        final Command<CommitResult> command =
                Command.push(Author.SYSTEM, repository.parent().name(), REPO_META, Revision.HEAD,
                             "Migrate the credentials", "", Markup.PLAINTEXT, changes);
        executeCommand(command);
        return true;
    }

    private boolean migrateMirrors(MetaRepository repository) throws Exception {
        final Map<String, Entry<?>> entries = repository.find(Revision.HEAD, LEGACY_MIRRORS_PATH + "*.json")
                                                        .join();
        if (entries.isEmpty()) {
            return false;
        }
        final List<Change<?>> changes = new ArrayList<>();
        for (Map.Entry<String, Entry<?>> entry : entries.entrySet()) {
            final JsonNode content = (JsonNode) entry.getValue().content();
            if (!content.isObject()) {
                warnInvalidConfig(entry, "mirror", content);
                continue;
            }
            try {
                final MirrorConfig mirrorConfig = Jackson.treeToValue(content, MirrorConfig.class);
                final String repoName = mirrorConfig.localRepo();
                if (repoName == null) {
                    warnInvalidConfig(entry, "mirror", content);
                    continue;
                }
                final String credentialName = mirrorConfig.credentialName();
                assert !credentialName.startsWith("projects/")
                        : "Legacy mirror configuration has invalid credential name: " + credentialName;

                // Migrate the credentialName to a project level one.
                final MirrorConfig newMirrorConfig = mirrorConfig.withCredentialName(
                        credentialName(repository.parent().name(), credentialName));
                changes.add(Change.ofJsonUpsert(mirrorFile(repoName, mirrorConfig.id()),
                                                Jackson.valueToTree(newMirrorConfig)));
            } catch (JsonProcessingException e) {
                warnInvalidConfig(entry, "mirror", content);
            }
        }
        if (changes.isEmpty()) {
            return false;
        }

        final Command<CommitResult> command =
                Command.push(Author.SYSTEM, repository.parent().name(), REPO_META, Revision.HEAD,
                             "Migrate the mirrors to each repository", "", Markup.PLAINTEXT, changes);
        executeCommand(command);
        return true;
    }

    private static void warnInvalidConfig(Map.Entry<String, Entry<?>> entry, String type, JsonNode content) {
        logger.warn("Ignoring an invalid {} configuration: {}, {}", type, entry.getKey(), content);
    }

    private void executeCommand(Command<CommitResult> command)
            throws InterruptedException, ExecutionException, TimeoutException {
        commandExecutor.execute(Command.forcePush(command)).get(1, TimeUnit.MINUTES);
    }

    private static class MirrorMigrationException extends RuntimeException {

        private static final long serialVersionUID = -3924318204193024460L;

        MirrorMigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
