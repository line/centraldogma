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

import static com.linecorp.centraldogma.internal.api.v1.MirrorRequest.projectMirrorCredentialId;
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
import com.linecorp.centraldogma.server.internal.replication.ZooKeeperCommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.repository.MirrorConfig;
import com.linecorp.centraldogma.server.management.ServerStatus;
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

        // Enter read-only mode.
        commandExecutor.execute(Command.updateServerStatus(ServerStatus.REPLICATION_ONLY))
                       .get(1, TimeUnit.MINUTES);
        logger.info("Starting Mirrors migration ...");
        if (commandExecutor instanceof ZooKeeperCommandExecutor) {
            logger.debug("Waiting for 10 seconds to make sure that all cluster have been notified of the " +
                         "read-only mode ...");
            Thread.sleep(10000);
        }

        final Stopwatch stopwatch = Stopwatch.createStarted();
        int numMigratedProjects = 0;
        try {
            for (Project project : projectManager.list().values()) {
                logger.info("Migrating mirrors in the project: {} ...", project.name());
                final MetaRepository repository = project.metaRepo();
                if (migrateMirrors(repository)) {
                    numMigratedProjects++;
                    logger.info("Mirrors in the project '{}' have been migrated.", project.name());
                } else {
                    logger.info("No legacy configurations of mirrors found in the project: {}.",
                                project.name());
                }
            }
            logMigrationJob(numMigratedProjects);
        } catch (Exception ex) {
            throw new MirrorMigrationException(
                    "Failed to migrate mirrors to each repository.", ex);
        }

        // Exit read-only mode.
        commandExecutor.execute(Command.updateServerStatus(ServerStatus.WRITABLE))
                       .get(1, TimeUnit.MINUTES);
        logger.info("Mirrors to each repository migration has been completed. (took: {} ms.)",
                    stopwatch.elapsed().toMillis());
    }

    private void logMigrationJob(int numMigratedProjects) throws Exception {
        final Map<String, Object> data =
                ImmutableMap.of("timestamp", Instant.now(),
                                "projects", numMigratedProjects);
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

    private boolean migrateMirrors(MetaRepository repository) throws Exception {
        final Map<String, Entry<?>> entries = repository.find(Revision.HEAD, LEGACY_MIRRORS_PATH + "*.json")
                                                        .join();
        if (entries.isEmpty()) {
            return false;
        }
        repository.parent().name();
        final List<Change<?>> changes = new ArrayList<>();
        for (Map.Entry<String, Entry<?>> entry : entries.entrySet()) {
            final JsonNode content = (JsonNode) entry.getValue().content();
            if (!content.isObject()) {
                warnInvalidMirrorConfig(entry, content);
                continue;
            }
            try {
                final MirrorConfig mirrorConfig = Jackson.treeToValue(content, MirrorConfig.class);
                final String repoName = mirrorConfig.localRepo();
                if (repoName == null) {
                    warnInvalidMirrorConfig(entry, content);
                    continue;
                }
                final MirrorConfig newMirrorConfig = mirrorConfig.withCredentialId(
                        projectMirrorCredentialId(repository.parent().name(), mirrorConfig.credentialId()));
                changes.add(Change.ofJsonUpsert(mirrorFile(repoName, mirrorConfig.id()),
                                                Jackson.valueToTree(newMirrorConfig)));
            } catch (JsonProcessingException e) {
                warnInvalidMirrorConfig(entry, content);
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

    private static void warnInvalidMirrorConfig(Map.Entry<String, Entry<?>> entry, JsonNode content) {
        logger.warn("Ignoring an invalid mirror configuration: {}, {}", entry.getKey(), content);
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
