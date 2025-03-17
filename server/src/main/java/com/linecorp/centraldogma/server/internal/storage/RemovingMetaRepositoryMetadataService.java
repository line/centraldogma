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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer.INTERNAL_PROJECT_DOGMA;
import static com.linecorp.centraldogma.server.storage.project.Project.REPO_DOGMA;
import static com.linecorp.centraldogma.server.storage.project.Project.REPO_META;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import com.linecorp.centraldogma.server.internal.metadata.ProjectMetadataTransformer;
import com.linecorp.centraldogma.server.metadata.ProjectMetadata;
import com.linecorp.centraldogma.server.metadata.RepositoryMetadata;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class RemovingMetaRepositoryMetadataService {

    private static final Logger logger = LoggerFactory.getLogger(
            RemovingMetaRepositoryMetadataService.class);

    @VisibleForTesting
    static final String REMOVING_META_REPOSITORY_METADATA_JOB_LOG =
            "/removing-meta-repository-metadata-job.json";

    private final ProjectManager projectManager;
    private final CommandExecutor commandExecutor;

    RemovingMetaRepositoryMetadataService(ProjectManager projectManager, CommandExecutor commandExecutor) {
        this.projectManager = projectManager;
        this.commandExecutor = commandExecutor;
    }

    void remove() throws Exception {
        if (hasMigrationLog()) {
            logger.debug("Meta repository's repository metadata of all projects have already been removed.");
            return;
        }

        final Stopwatch stopwatch = Stopwatch.createStarted();
        int numMetaRemovedProjects = 0;
        for (Project project : projectManager.list().values()) {
            final String projectName = project.name();
            if (INTERNAL_PROJECT_DOGMA.equals(projectName)) {
                continue;
            }

            logger.info("Removing meta repository metadata in the project: {} ...", projectName);
            try {
                removeMetaRepositoryMetadata(projectName);
                numMetaRemovedProjects++;
                logger.info("Meta repository metadata in the project '{}' is removed.", projectName);
            } catch (Throwable t) {
                logger.warn("Failed to remove meta repository metadata. project: {}", projectName, t);
                continue;
            }
        }
        logMigrationJob(numMetaRemovedProjects);
        logger.info("Removing meta repository metadata of {} projects has been completed. (took: {} ms.)",
                    numMetaRemovedProjects, stopwatch.elapsed().toMillis());
    }

    private void logMigrationJob(int numMetaRemovedProjects) throws Exception {
        final Map<String, Object> data =
                ImmutableMap.of("timestamp", Instant.now(),
                                "meta_removed", numMetaRemovedProjects);
        final Change<JsonNode> change = Change.ofJsonUpsert(REMOVING_META_REPOSITORY_METADATA_JOB_LOG,
                                                            Jackson.writeValueAsString(data));
        final Command<CommitResult> command =
                Command.push(Author.SYSTEM, INTERNAL_PROJECT_DOGMA,
                             REPO_DOGMA, Revision.HEAD,
                             "Removing meta repository metadata has been done.", "",
                             Markup.PLAINTEXT, change);
        executeCommand(command);
    }

    private boolean hasMigrationLog() throws Exception {
        final Project internalProj = projectManager.get(INTERNAL_PROJECT_DOGMA);
        final Repository repository = internalProj.repos().get(REPO_DOGMA);
        final Map<String, Entry<?>> entries = repository.find(Revision.HEAD,
                                                              REMOVING_META_REPOSITORY_METADATA_JOB_LOG).get();
        final Entry<?> entry = entries.get(REMOVING_META_REPOSITORY_METADATA_JOB_LOG);
        return entry != null;
    }

    private void removeMetaRepositoryMetadata(String projectName) throws Exception {
        final ProjectMetadataTransformer transformer =
                new ProjectMetadataTransformer((headRevision, projectMetadata) -> {
                    // Raises an exception if the meta not exist. The exception will be caught and the removing
                    // job will continue.
                    projectMetadata.repo(REPO_META);
                    final Map<String, RepositoryMetadata> newRepos =
                            projectMetadata.repos().entrySet().stream()
                                           .filter(e -> !e.getKey().equals(REPO_META))
                                           .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
                    return new ProjectMetadata(projectMetadata.name(),
                                               newRepos,
                                               projectMetadata.members(),
                                               projectMetadata.tokens(),
                                               projectMetadata.creation(),
                                               projectMetadata.removal());
                });
        executeCommand(Command.transform(null, Author.SYSTEM, projectName,
                                         REPO_DOGMA, // Push to the dogma repository which has /metadata.json
                                         Revision.HEAD, "Remove the meta repository metadata", "",
                                         Markup.PLAINTEXT, transformer));
    }

    private void executeCommand(Command<CommitResult> command)
            throws InterruptedException, ExecutionException, TimeoutException {
        commandExecutor.execute(command).get(1, TimeUnit.MINUTES);
    }
}
