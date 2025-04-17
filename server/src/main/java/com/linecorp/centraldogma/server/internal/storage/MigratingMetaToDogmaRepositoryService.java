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
package com.linecorp.centraldogma.server.internal.storage;

import static com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer.INTERNAL_PROJECT_DOGMA;
import static com.linecorp.centraldogma.server.storage.project.Project.REPO_DOGMA;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.RepositoryStatus;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;

public final class MigratingMetaToDogmaRepositoryService {

    private static final Logger logger = LoggerFactory.getLogger(
            MigratingMetaToDogmaRepositoryService.class);

    // Will be stored in dogma/dogma
    public static final String META_TO_DOGMA_MIGRATION_JOB = "/meta-to-dogma-migration-job.json";

    // Will be stored in {project}/dogma
    public static final String META_TO_DOGMA_MIGRATED = "/meta-to-dogma-migrated.json";

    private final ProjectManager projectManager;
    private final CommandExecutor commandExecutor;
    private final MetadataService metadataService;

    MigratingMetaToDogmaRepositoryService(ProjectManager projectManager, CommandExecutor commandExecutor,
                                          InternalProjectInitializer internalProjectInitializer) {
        this.projectManager = projectManager;
        this.commandExecutor = commandExecutor;
        metadataService = new MetadataService(projectManager, commandExecutor, internalProjectInitializer);
    }

    boolean hasMigrationLog() throws ExecutionException, InterruptedException {
        final Project internalProj = projectManager.get(INTERNAL_PROJECT_DOGMA);
        final Repository repository = internalProj.repos().get(REPO_DOGMA);
        final Entry<JsonNode> entry =
                repository.getOrNull(Revision.HEAD, Query.ofJson(META_TO_DOGMA_MIGRATION_JOB)).get();
        return entry != null;
    }

    void migrate() throws Exception {
        logger.info("Starting to migrate meta repository to dogma repository of all projects ...");
        final Stopwatch stopwatch = Stopwatch.createStarted();
        int numMigratedProjects = 0;

        for (Project project : projectManager.list().values()) {
            final String projectName = project.name();
            if (INTERNAL_PROJECT_DOGMA.equals(projectName)) {
                continue;
            }

            if (REPO_DOGMA.equals(project.metaRepo().name())) {
                logger.debug("The project '{}' has already been migrated.", projectName);
                continue;
            }
            logger.info("Migrating meta repository to dogma repository in the project: {} ...", projectName);

            metadataService.updateRepositoryStatus(
                    Author.SYSTEM, projectName, REPO_DOGMA, RepositoryStatus.READ_ONLY).join();
            // Wait for the repository status to be updated.
            Thread.sleep(3000L);
            logger.info("Dogma repository in the project '{}' is set to read-only.", projectName);
            try {
                migrateMetaRepository(project);
                numMigratedProjects++;
                logger.info("Meta repository in the project '{}' is migrated to dogma repository.",
                            projectName);
            } catch (Throwable t) {
                logger.warn("Failed to migrate meta repository. project: {}", projectName, t);
                // Do not continue the migration if the migration fails.
                return;
            }

            try {
                commandExecutor.execute(Command.resetMetaRepository(Author.SYSTEM, projectName))
                               .get(1, TimeUnit.MINUTES);
            } catch (Throwable t) {
                logger.warn("Failed to reset meta repository. project: {}", projectName, t);
                // Do not continue the migration if the migration fails.
                return;
            }
            metadataService.updateRepositoryStatus(
                    Author.SYSTEM, projectName, REPO_DOGMA, RepositoryStatus.ACTIVE).join();
            logger.info("Dogma repository in the project '{}' is set to active.", projectName);
        }
        logMigrationJob(numMigratedProjects);
        logger.info("Migrating meta repository to dogma repository of {} projects has been completed." +
                    "(took: {} ms.)", numMigratedProjects, stopwatch.elapsed().toMillis());
    }

    private void logMigrationJob(int numMigratedProjects) throws Exception {
        final Map<String, Object> data =
                ImmutableMap.of("timestamp", Instant.now(),
                                "numMigratedProjects", numMigratedProjects);
        final Change<JsonNode> change = Change.ofJsonUpsert(META_TO_DOGMA_MIGRATION_JOB,
                                                            Jackson.writeValueAsString(data));
        final Command<CommitResult> command =
                Command.push(Author.SYSTEM, INTERNAL_PROJECT_DOGMA,
                             REPO_DOGMA, Revision.HEAD,
                             "Migrating meta repository to dogma repository has been done.", "",
                             Markup.PLAINTEXT, change);
        executeCommand(command);
    }

    private void migrateMetaRepository(Project project) throws Exception {
        final Repository metaRepository = project.repos().get(Project.REPO_META);
        final Map<String, Entry<?>> entryMap =
                metaRepository.find(Revision.HEAD, "/credentials/*.json,/repos/**.json").get();
        final ImmutableList.Builder<Change<?>> builder =
                ImmutableList.builderWithExpectedSize(entryMap.size() + 1);
        for (Map.Entry<String, Entry<?>> entry : entryMap.entrySet()) {
            //noinspection unchecked
            final Entry<JsonNode> value = (Entry<JsonNode>) entry.getValue();
            builder.add(Change.ofJsonUpsert(entry.getKey(), value.content()));
        }
        builder.add(Change.ofJsonUpsert(META_TO_DOGMA_MIGRATED,
                                        Jackson.writeValueAsString(
                                                ImmutableMap.of("timestamp", Instant.now()))));

        executeCommand(Command.forcePush(Command.push(Author.SYSTEM, project.name(), REPO_DOGMA,
                                                      Revision.HEAD,
                                                      "Migrate the meta repository to dogma repository", "",
                                                      Markup.PLAINTEXT, builder.build())));
    }

    private void executeCommand(Command<CommitResult> command)
            throws InterruptedException, ExecutionException, TimeoutException {
        commandExecutor.execute(command).get(1, TimeUnit.MINUTES);
    }
}
