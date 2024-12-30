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
package com.linecorp.centraldogma.server.internal;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;

public final class MetadataRolesMigrationPlugin implements Plugin {

    private static final Logger logger = LoggerFactory.getLogger(MetadataRolesMigrationPlugin.class);

    @VisibleForTesting
    static final String METADATA_ROLES_MIGRATION_JOB_LOG =
            "/metadata-roles-job.json";

    @Override
    public PluginTarget target(CentralDogmaConfig config) {
        return PluginTarget.LEADER_ONLY;
    }

    @Override
    public CompletionStage<Void> start(PluginContext context) {
        if (hasMigrationLog(context)) {
            logger.debug("Metadata roles have already been migrated. Skipping auto migration...");
            return UnmodifiableFuture.completedFuture(null);
        }
        logger.info("Starting metadata roles migration ...");

        final MetadataService metadataService = new MetadataService(context.projectManager(),
                                                                    context.commandExecutor());
        final Stopwatch stopwatch = Stopwatch.createStarted();
        for (Project project : context.projectManager().list().values()) {
            final String name = project.name();
            try {
                metadataService.migrateMetadata(name).join();
            } catch (Exception ex) {
                // No need to rollback because the migration is not committed. Just log the error.
                logger.warn("Failed to migrate metadata roles of {}", name, ex);
                return UnmodifiableFuture.completedFuture(null);
            }
        }
        logMigrationJob(context.commandExecutor());
        logger.info("Metadata roles migration has been completed. (took: {} ms.)",
                    stopwatch.elapsed().toMillis());
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> stop(PluginContext context) {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public Class<?> configType() {
        // Return the plugin class itself because it does not have a configuration.
        return getClass();
    }

    private static boolean hasMigrationLog(PluginContext context) {
        final Project internalProj =
                context.projectManager().get(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA);
        final Repository repository = internalProj.repos().get(Project.REPO_DOGMA);
        final Entry<JsonNode> entry = repository.getOrNull(Revision.HEAD, Query.ofJson(
                METADATA_ROLES_MIGRATION_JOB_LOG)).join();
        return entry != null;
    }

    private static void logMigrationJob(CommandExecutor commandExecutor) {
        final ImmutableMap<String, Object> data = ImmutableMap.of("timestamp", Instant.now());
        final Change<JsonNode> change;
        try {
            change = Change.ofJsonUpsert(METADATA_ROLES_MIGRATION_JOB_LOG,
                                         Jackson.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            // Should never reach here.
            throw new Error(e);
        }
        final Command<CommitResult> command =
                Command.push(Author.SYSTEM, InternalProjectInitializer.INTERNAL_PROJECT_DOGMA,
                             Project.REPO_DOGMA, Revision.HEAD,
                             "Migration of metadata roles has been done", "",
                             Markup.PLAINTEXT, change);
        commandExecutor.execute(command).join();
    }
}
