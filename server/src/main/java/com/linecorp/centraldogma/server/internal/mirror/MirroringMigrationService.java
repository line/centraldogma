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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.PATH_CREDENTIALS;
import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.PATH_MIRRORS;
import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.credentialFile;
import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.mirrorFile;
import static com.linecorp.centraldogma.server.internal.storage.repository.MirrorConfig.DEFAULT_SCHEDULE;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
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
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryMetadataException;
import com.linecorp.centraldogma.server.management.ServerStatus;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class MirroringMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(MirroringMigrationService.class);

    @VisibleForTesting
    static final String PATH_LEGACY_MIRRORS = "/mirrors.json";
    @VisibleForTesting
    static final String PATH_LEGACY_CREDENTIALS = "/credentials.json";
    @VisibleForTesting
    static final String PATH_LEGACY_MIRRORS_BACKUP = PATH_LEGACY_MIRRORS + ".bak";
    @VisibleForTesting
    static final String PATH_LEGACY_CREDENTIALS_BACKUP = PATH_LEGACY_CREDENTIALS + ".bak";
    @VisibleForTesting
    static final String MIRROR_MIGRATION_JOB_LOG = "/mirror-migration-job.json";

    private final ProjectManager projectManager;
    private final CommandExecutor commandExecutor;

    @Nullable
    private List<String> shortWords;

    MirroringMigrationService(ProjectManager projectManager, CommandExecutor commandExecutor) {
        this.projectManager = projectManager;
        this.commandExecutor = commandExecutor;
    }

    void migrate() throws Exception {
        if (hasMigrationLog()) {
            logger.debug("Mirrors and credentials have already been migrated. Skipping auto migration...");
            return;
        }

        // Enter read-only mode.
        commandExecutor.execute(Command.updateServerStatus(ServerStatus.REPLICATION_ONLY))
                       .get(1, TimeUnit.MINUTES);
        logger.info("Starting Mirrors and credentials migration ...");
        if (commandExecutor instanceof ZooKeeperCommandExecutor) {
            logger.debug("Waiting for 30 seconds to make sure that all cluster have been notified of the " +
                         "read-only mode ...");
            Thread.sleep(30000);
        }

        final Stopwatch stopwatch = Stopwatch.createStarted();
        int numMigratedProjects = 0;
        try {
            for (Project project : projectManager.list().values()) {
                logger.info("Migrating mirrors and credentials in the project: {} ...", project.name());
                boolean processed = false;
                final MetaRepository repository = project.metaRepo();
                processed |= migrateCredentials(repository);
                // Update the credential IDs in the mirrors.json file.
                processed |= migrateMirrors(repository);
                if (processed) {
                    numMigratedProjects++;
                    logger.info("Mirrors and credentials in the project: {} have been migrated.",
                                project.name());
                } else {
                    logger.info("No legacy configurations of mirrors and credentials found in the project: {}.",
                                project.name());
                }
            }
            logMigrationJob(numMigratedProjects);
        } catch (Exception ex) {
            final MirrorMigrationException mirrorException = new MirrorMigrationException(
                    "Failed to migrate mirrors and credentials. Rollback to the legacy configurations", ex);
            try {
                rollbackMigration();
            } catch (Exception ex0) {
                ex0.addSuppressed(mirrorException);
                throw new MirrorMigrationException("Failed to rollback the mirror migration:", ex0);
            }
            throw mirrorException;
        }

        // Exit read-only mode.
        commandExecutor.execute(Command.updateServerStatus(ServerStatus.WRITABLE))
                       .get(1, TimeUnit.MINUTES);
        logger.info("Mirrors and credentials migration has been completed. (took: {} ms.)",
                    stopwatch.elapsed().toMillis());

        shortWords = null;
    }

    private void logMigrationJob(int numMigratedProjects) throws Exception {
        final ImmutableMap<String, Object> data =
                ImmutableMap.of("timestamp", Instant.now(),
                                "projects", numMigratedProjects);
        final Change<JsonNode> change = Change.ofJsonUpsert(MIRROR_MIGRATION_JOB_LOG,
                                                            Jackson.writeValueAsString(data));
        final Command<CommitResult> command =
                Command.push(Author.SYSTEM, InternalProjectInitializer.INTERNAL_PROJECT_DOGMA,
                             Project.REPO_DOGMA, Revision.HEAD,
                             "Migration of mirrors and credentials has been done", "",
                             Markup.PLAINTEXT, change);
        executeCommand(command);
    }

    private void removeMigrationJobLog() throws Exception {
        if (!hasMigrationLog()) {
            // Maybe the migration job was failed before writing the log.
            return;
        }
        final Change<Void> change = Change.ofRemoval(MIRROR_MIGRATION_JOB_LOG);
        final Command<CommitResult> command =
                Command.push(Author.SYSTEM, InternalProjectInitializer.INTERNAL_PROJECT_DOGMA,
                             Project.REPO_DOGMA, Revision.HEAD,
                             "Remove the migration job log", "",
                             Markup.PLAINTEXT, change);
        executeCommand(command);
    }

    private boolean hasMigrationLog() throws Exception {
        final Project internalProj = projectManager.get(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA);
        final Repository repository = internalProj.repos().get(Project.REPO_DOGMA);
        final Map<String, Entry<?>> entries = repository.find(Revision.HEAD, MIRROR_MIGRATION_JOB_LOG).get();
        final Entry<?> entry = entries.get(MIRROR_MIGRATION_JOB_LOG);
        return entry != null;
    }

    private boolean migrateMirrors(MetaRepository repository) throws Exception {
        final ArrayNode mirrors = getLegacyMetaData(repository, PATH_LEGACY_MIRRORS);
        if (mirrors == null) {
            return false;
        }

        final List<MirrorCredential> credentials = repository.credentials()
                                                             .get(30, TimeUnit.SECONDS);

        final Set<String> mirrorIds = new HashSet<>();
        for (JsonNode mirror : mirrors) {
            if (!mirror.isObject()) {
                logger.warn("A mirror config must be an object: {} (project: {})", mirror,
                            repository.parent().name());
                continue;
            }
            try {
                migrateMirror(repository, (ObjectNode) mirror, mirrorIds, credentials);
            } catch (Exception e) {
                logger.warn("Failed to migrate a mirror config: {} (project: {})", mirror,
                            repository.parent().name(), e);
                throw e;
            }
        }
        // Back up the old mirrors.json file and don't use it anymore.
        rename(repository, PATH_LEGACY_MIRRORS, PATH_LEGACY_MIRRORS_BACKUP, false);

        return true;
    }

    private void migrateMirror(MetaRepository repository, ObjectNode mirror, Set<String> mirrorIds,
                               List<MirrorCredential> credentials) throws Exception {
        String id;
        final JsonNode idNode = mirror.get("id");
        if (idNode == null) {
            // Fill the 'id' field with a random value if not exists.
            id = generateIdForMirror(repository.parent().name(), mirror);
        } else {
            id = idNode.asText();
        }
        id = uniquify(id, mirrorIds);
        mirror.put("id", id);

        fillCredentialId(repository, mirror, credentials);
        if (mirror.get("schedule") == null) {
            mirror.put("schedule", DEFAULT_SCHEDULE);
        }
        mirrorIds.add(id);

        final String jsonFile = mirrorFile(id);
        final Command<CommitResult> command =
                Command.push(Author.SYSTEM, repository.parent().name(), repository.name(), Revision.HEAD,
                             "Migrate the mirror " + id + " in '" + PATH_LEGACY_MIRRORS + "' into '" +
                             jsonFile + "'.", "", Markup.PLAINTEXT, Change.ofJsonUpsert(jsonFile, mirror));

        executeCommand(command);
    }

    private void rollbackMigration() throws Exception {
        for (Project project : projectManager.list().values()) {
            logger.info("Rolling back the migration of mirrors and credentials in the project: {} ...",
                        project.name());
            final MetaRepository metaRepository = project.metaRepo();
            rollbackMigration(metaRepository, PATH_MIRRORS, PATH_LEGACY_MIRRORS,
                              PATH_LEGACY_MIRRORS_BACKUP);
            rollbackMigration(metaRepository, PATH_CREDENTIALS, PATH_LEGACY_CREDENTIALS,
                              PATH_LEGACY_CREDENTIALS_BACKUP);
            removeMigrationJobLog();
        }
    }

    private void rollbackMigration(MetaRepository repository, String targetDirectory, String originalFile,
                                   String backupFile) throws Exception {
        // Delete all files in the target directory
        final Map<String, Entry<?>> entries = repository.find(Revision.HEAD, targetDirectory + "**")
                                                        .get();
        final List<Change<?>> changes = entries.keySet().stream().map(Change::ofRemoval)
                                                  .collect(toImmutableList());
        final Command<CommitResult> command =
                Command.push(Author.SYSTEM, repository.parent().name(),
                             repository.name(), Revision.HEAD,
                             "Rollback the migration of " + targetDirectory, "",
                             Markup.PLAINTEXT, changes);
        try {
            executeCommand(command);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new MirrorMigrationException("Failed to rollback the migration of " + targetDirectory, e);
        }
        // Revert the backup file to the original file if exists.
        final Entry<?> backup = repository.getOrNull(Revision.HEAD, backupFile).get();
        if (backup != null) {
            rename(repository, backupFile, originalFile, true);
        }
    }

    private CommitResult executeCommand(Command<CommitResult> command)
            throws InterruptedException, ExecutionException, TimeoutException {
        return commandExecutor.execute(Command.forcePush(command)).get(1, TimeUnit.MINUTES);
    }

    private static void fillCredentialId(MetaRepository repository, ObjectNode mirror,
                                         List<MirrorCredential> credentials) {
        final JsonNode credentialId = mirror.get("credentialId");
        if (credentialId != null) {
            return;
        }
        final JsonNode remoteUri = mirror.get("remoteUri");
        if (remoteUri == null) {
            // An invalid mirror config.
            return;
        }

        final String remoteUriText = remoteUri.asText();
        final MirrorCredential credential = MirrorConfig.findCredential(credentials, URI.create(remoteUriText),
                                                                        null);
        if (credential == MirrorCredential.FALLBACK) {
            logger.warn("Failed to find a credential for the mirror: {}, project: {}. " +
                        "Using the fallback credential.", mirror, repository.parent().name());
        }
        mirror.put("credentialId", credential.id());
    }

    /**
     * Migrate the legacy {@code credentials.json} file into the {@code /credentials/<id>.json} directory.
     * While migrating, the {@code id} field of each credential is filled with a random value if absent.
     */
    private boolean migrateCredentials(MetaRepository repository) throws Exception {
        final ArrayNode credentials = getLegacyMetaData(repository, PATH_LEGACY_CREDENTIALS);
        if (credentials == null) {
            return false;
        }

        final Set<String> credentialIds = new HashSet<>();
        int index = 0;
        for (JsonNode credential : credentials) {
            if (!credential.isObject()) {
                logger.warn("A credential config at {} must be an object: {} (project: {})", index,
                            credential.getNodeType(),
                            repository.parent().name());
            } else {
                try {
                    migrateCredential(repository, (ObjectNode) credential, credentialIds);
                } catch (Exception e) {
                    logger.warn("Failed to migrate the credential config in project {}",
                                repository.parent().name(), e);
                    throw e;
                }
            }
            index++;
        }

        // Back up the old credentials.json file and don't use it anymore.
        rename(repository, PATH_LEGACY_CREDENTIALS, PATH_LEGACY_CREDENTIALS_BACKUP, false);
        return true;
    }

    private void migrateCredential(MetaRepository repository, ObjectNode credential, Set<String> credentialIds)
            throws Exception {
        String id;
        final JsonNode idNode = credential.get("id");
        final String projectName = repository.parent().name();
        if (idNode == null) {
            // Fill the 'id' field with a random value if not exists.
            id = generateIdForCredential(projectName);
        } else {
            id = idNode.asText();
        }
        id = uniquify(id, credentialIds);
        credential.put("id", id);
        credentialIds.add(id);

        final String jsonFile = credentialFile(id);
        final Command<CommitResult> command =
                Command.push(Author.SYSTEM, projectName, repository.name(), Revision.HEAD,
                             "Migrate the credential '" + id + "' in '" + PATH_LEGACY_CREDENTIALS +
                             "' into '" + jsonFile + "'.", "", Markup.PLAINTEXT,
                             Change.ofJsonUpsert(jsonFile, credential));
        executeCommand(command);
    }

    @Nullable
    private static ArrayNode getLegacyMetaData(MetaRepository repository, String path)
            throws InterruptedException, ExecutionException {
        final Map<String, Entry<?>> entries = repository.find(Revision.HEAD, path, ImmutableMap.of())
                                                        .get();
        final Entry<?> entry = entries.get(path);
        if (entry == null) {
            return null;
        }

        final JsonNode content = (JsonNode) entry.content();
        if (!content.isArray()) {
            throw new RepositoryMetadataException(
                    path + " must be an array: " + content.getNodeType());
        }
        return (ArrayNode) content;
    }

    private CommitResult rename(MetaRepository repository, String oldPath, String newPath, boolean rollback)
            throws Exception {
        final String summary;
        if (rollback) {
            summary = "Rollback the migration of " + newPath;
        } else {
            summary = "Back up the legacy " + oldPath + " into " + newPath;
        }
        final Command<CommitResult> command = Command.push(Author.SYSTEM, repository.parent().name(),
                                                           repository.name(), Revision.HEAD,
                                                           summary,
                                                           "",
                                                           Markup.PLAINTEXT,
                                                           Change.ofRename(oldPath, newPath));
        return executeCommand(command);
    }

    /**
     * Generates a reproducible ID for the given mirror.
     * Pattern: {@code mirror-<projectName>-<localRepo>-<shortWord>}.
     */
    private String generateIdForMirror(String projectName, ObjectNode mirror) {
        final String id = "mirror-" + projectName + '-' + mirror.get("localRepo").asText();
        return id + '-' + getShortWord(id);
    }

    private String getShortWord(String id) {
        if (shortWords == null) {
            shortWords = buildShortWords();
        }
        final int index = Math.abs(id.hashCode()) % shortWords.size();
        return shortWords.get(index);
    }

    /**
     * Generates a reproducible ID for the given credential.
     * Pattern: {@code credential-<projectName>-<shortWord>}.
     */
    private String generateIdForCredential(String projectName) {
        final String id = "credential-" + projectName;
        return id + '-' + getShortWord(projectName);
    }

    private static String uniquify(String id, Set<String> existingIds) {
        int suffix = 1;
        String maybeUnique = id;
        while (existingIds.contains(maybeUnique)) {
            maybeUnique = id + '-' + suffix++;
        }
        return maybeUnique;
    }

    private static List<String> buildShortWords() {
        // TODO(ikhoon) Remove 'short_wordlist.txt' if Central Dogma version has been updated enough and
        //              we can assume that all users have already migrated.
        final InputStream is = MirroringMigrationService.class.getResourceAsStream("short_wordlist.txt");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            final ImmutableList.Builder<String> words = ImmutableList.builder();
            words.add(reader.readLine());
            return words.build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static class MirrorMigrationException extends RuntimeException {

        private static final long serialVersionUID = -3924318204193024460L;

        MirrorMigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
