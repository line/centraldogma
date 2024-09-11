/*
 * Copyright 2023 LINE Corporation
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

import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.PATH_CREDENTIALS;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryType;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class RemovingHostnamePatternsService {

    private static final Logger logger = LoggerFactory.getLogger(RemovingHostnamePatternsService.class);

    @VisibleForTesting
    static final String REMOVING_HOSTNAME_JOB_LOG = "/removing-hostname-job.json";

    private final ProjectManager projectManager;
    private final CommandExecutor commandExecutor;

    RemovingHostnamePatternsService(ProjectManager projectManager, CommandExecutor commandExecutor) {
        this.projectManager = projectManager;
        this.commandExecutor = commandExecutor;
    }

    void start() throws Exception {
        if (hasJobLog()) {
            return;
        }
        logger.info("Start removing hostnamePatterns in credential ...");

        final Stopwatch stopwatch = Stopwatch.createStarted();
        int numProjects = 0;
        for (Project project : projectManager.list().values()) {
            if (InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(project.name())) {
                continue;
            }
            try {
                logger.info("Removing hostnamePatterns in credential files in the project: {} ...",
                            project.name());
                final List<Change<?>> changes = new ArrayList<>();
                final MetaRepository repository = project.metaRepo();
                for (Entry<?> entry : repository.find(Revision.HEAD, PATH_CREDENTIALS + "**")
                                                .get().values()) {
                    if (entry.type() != EntryType.JSON) {
                        continue;
                    }
                    final JsonNode content = (JsonNode) entry.content();
                    if (content.get("hostnamePatterns") == null) {
                        continue;
                    }
                    changes.add(Change.ofJsonUpsert(entry.path(),
                                                    ((ObjectNode) content).without("hostnamePatterns")));
                }

                if (changes.isEmpty()) {
                    continue;
                }

                numProjects++;
                logger.info("hostnamePatterns in credentials are removed in the project: {}",
                            project.name());

                executeCommand(Command.push(
                        Author.SYSTEM, project.name(), Project.REPO_META, Revision.HEAD,
                        "Remove hostnamePatterns in credentials.", "", Markup.PLAINTEXT,
                        changes));
            } catch (Throwable t) {
                logger.warn("Failed to remove hostnamePatterns in credential files in the project: {}",
                            project.name(), t);
            }
        }
        logger.info("hostnamePatterns are removed in {} projects. (took: {} ms.)",
                    numProjects, stopwatch.elapsed().toMillis());
        logRemovingJob(numProjects);
    }

    private boolean hasJobLog() throws Exception {
        final Project internalProj = projectManager.get(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA);
        final Repository repository = internalProj.repos().get(Project.REPO_DOGMA);
        final Map<String, Entry<?>> entries = repository.find(Revision.HEAD, REMOVING_HOSTNAME_JOB_LOG).get();
        final Entry<?> entry = entries.get(REMOVING_HOSTNAME_JOB_LOG);
        return entry != null;
    }

    private CommitResult executeCommand(Command<CommitResult> command)
            throws InterruptedException, ExecutionException, TimeoutException {
        return commandExecutor.execute(command).get(1, TimeUnit.MINUTES);
    }

    private void logRemovingJob(int numProjects) throws Exception {
        final ImmutableMap<String, Object> data =
                ImmutableMap.of("timestamp", Instant.now(),
                                "projects", numProjects);
        final Change<JsonNode> change = Change.ofJsonUpsert(REMOVING_HOSTNAME_JOB_LOG,
                                                            Jackson.writeValueAsString(data));
        executeCommand(Command.push(Author.SYSTEM, InternalProjectInitializer.INTERNAL_PROJECT_DOGMA,
                                    Project.REPO_DOGMA, Revision.HEAD,
                                    "Removing hostnamePatterns from " + numProjects +
                                    " projects has been done.", "", Markup.PLAINTEXT, change));
    }
}
