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
import static com.linecorp.centraldogma.server.internal.storage.repository.DefaultMetaRepository.PATH_MIRRORS;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryMetadataException;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

class MirroringMigrationService {

    private static final String PATH_MIRRORS_BACKUP = PATH_MIRRORS + ".bak";
    private static final String PATH_CREDENTIALS_BACKUP = PATH_CREDENTIALS + ".bak";

    private final ProjectManager projectManager;

    @Nullable
    private List<String> shortWords;

    MirroringMigrationService(ProjectManager projectManager) {
        this.projectManager = projectManager;
    }

    void migrate() throws Exception {
        for (Project project : projectManager.list().values()) {
            migrateMirrors(project);
            migrateCredentials(project);
        }
        shortWords = null;
    }

    private void migrateMirrors(Project project) throws Exception {
        final ArrayNode mirrors = getMetaData(project, PATH_MIRRORS);
        if (mirrors == null) {
            return;
        }

        final Set<String> mirrorIds = new HashSet<>();
        for (JsonNode mirror : mirrors) {
            if (!mirror.isObject()) {
                throw new RepositoryMetadataException(
                        "A mirror config must be an object: " + mirror.getNodeType());
            }
            migrateMirror(project, (ObjectNode) mirror, mirrorIds);
        }

        // Back up the old mirrors.json file and don't use it anymore.
        rename(project, PATH_MIRRORS, PATH_MIRRORS_BACKUP);
    }

    private void migrateMirror(Project project, ObjectNode mirror, Set<String> mirrorIds) throws Exception {
        String id;
        final JsonNode idNode = mirror.get("id");
        if (idNode == null) {
            // Fill the 'id' field with a random value if not exists.
            id = generateRandomIdForMirror(project.name(), mirror);
        } else {
            id = idNode.asText();
        }
        id = uniquify(id, mirrorIds);
        mirror.put("id", id);
        mirrorIds.add(id);

        final String jsonFile = "/mirrors/" + id + ".json";
        project.metaRepo()
               .commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                       "Migrate the mirror " + id + " in '" + PATH_MIRRORS + "' into '" + jsonFile + "'.",
                       Change.ofJsonUpsert(jsonFile, mirror))
               .get();
    }

    private void migrateCredentials(Project project) throws Exception {
        final ArrayNode credentials = getMetaData(project, PATH_CREDENTIALS);
        if (credentials == null) {
            return;
        }

        final Set<String> credentialIds = new HashSet<>();
        for (JsonNode credential : credentials) {
            if (!credential.isObject()) {
                throw new RepositoryMetadataException(
                        "A credential config must be an object: " + credential.getNodeType());
            }
            migrateCredential(project, (ObjectNode) credential, credentialIds);
        }

        // Back up the old credentials.json file and don't use it anymore.
        rename(project, PATH_CREDENTIALS, PATH_CREDENTIALS_BACKUP);
    }

    private void migrateCredential(Project project, ObjectNode credential, Set<String> credentialIds)
            throws Exception {
        String id;
        final JsonNode idNode = credential.get("id");
        if (idNode == null) {
            // Fill the 'id' field with a random value if not exists.
            id = generateRandomIdForCredential(project.name());
        } else {
            id = idNode.asText();
        }
        id = uniquify(id, credentialIds);
        credential.put("id", id);
        credentialIds.add(id);

        final String jsonFile = "/credentials/" + id + ".json";
        project.metaRepo()
               .commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                       "Migrate the credential '" + id + "' in '" + PATH_CREDENTIALS +
                       "' into '" + jsonFile + "'.",
                       Change.ofJsonUpsert(jsonFile, credential))
               .get();
    }

    @Nullable
    private static ArrayNode getMetaData(Project project, String path)
            throws InterruptedException, ExecutionException {
        final Map<String, Entry<?>> entries = project.metaRepo()
                                                     .find(Revision.HEAD, path, ImmutableMap.of())
                                                     .get();
        final Entry<?> entry = entries.get(path);
        if (entry == null) {
            return null;
        }

        final JsonNode credentialJson = (JsonNode) entry.content();
        if (!credentialJson.isArray()) {
            throw new RepositoryMetadataException(
                    path + " must be an array: " + credentialJson.getNodeType());
        }
        return (ArrayNode) credentialJson;
    }

    private static CommitResult rename(Project project, String oldPath, String newPath) throws Exception {
        return project.metaRepo()
                      .commit(Revision.HEAD, System.currentTimeMillis(), Author.SYSTEM,
                              "Rename " + oldPath + " into " + newPath,
                              Change.ofRename(oldPath, newPath))
                      .get();
    }

    /**
     * Generates a random ID for the given mirror.
     * Pattern: {@code mirror-<projectName>-<localRepo>-<shortWord>}.
     */
    private String generateRandomIdForMirror(String projectName, ObjectNode mirror) {
        if (shortWords == null) {
            shortWords = buildShortWords();
        }
        final int rand = ThreadLocalRandom.current().nextInt(shortWords.size());
        final String shortWord = shortWords.get(rand);
        return "mirror-" + projectName + '-' + mirror.get("localRepo").asText() + '-' +
               shortWord;
    }

    /**
     * Generates a random ID for the given credential.
     * Pattern: {@code credential-<projectName>-<shortWord>}.
     */
    private String generateRandomIdForCredential(String projectName) {
        if (shortWords == null) {
            shortWords = buildShortWords();
        }
        final int rand = ThreadLocalRandom.current().nextInt(shortWords.size());
        final String shortWord = shortWords.get(rand);
        return "credential-" + projectName + '-' + shortWord;
    }

    private static String uniquify(String id, Set<String> existingIds) {
        while (existingIds.contains(id)) {
            id += '-' + Integer.toHexString(ThreadLocalRandom.current().nextInt());
        }
        return id;
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
}
