/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.storage.repository;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.cronutils.model.Cron;
import com.cronutils.model.field.CronField;
import com.cronutils.model.field.CronFieldName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.MirrorDto;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirrorUtil;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;

public final class DefaultMetaRepository extends RepositoryWrapper implements MetaRepository {

    private static final String PATH_CREDENTIALS = "/credentials/";

    private static final String PATH_MIRRORS = "/mirrors/";

    public static boolean isMetaFile(String path) {
        return path.endsWith(".json") && (path.startsWith(PATH_CREDENTIALS) || path.startsWith(PATH_MIRRORS));
    }

    public static String credentialFile(String credentialId) {
        return PATH_CREDENTIALS + credentialId + ".json";
    }

    public static String mirrorFile(String mirrorId) {
        return PATH_MIRRORS + mirrorId + ".json";
    }

    public DefaultMetaRepository(Repository repo) {
        super(repo);
    }

    @Override
    public CompletableFuture<List<Mirror>> mirrors(boolean includeDisabled) {
        if (includeDisabled) {
            return allMirrors();
        }
        return allMirrors().thenApply(mirrors -> {
            return mirrors.stream().filter(Mirror::enabled).collect(toImmutableList());
        });
    }

    @Override
    public CompletableFuture<Mirror> mirror(String id) {
        final String mirrorFile = mirrorFile(id);
        return find(mirrorFile).thenCompose(entries -> {
            @SuppressWarnings("unchecked")
            final Entry<JsonNode> entry = (Entry<JsonNode>) entries.get(mirrorFile);
            if (entry == null) {
                throw new EntryNotFoundException("failed to find credential '" + mirrorFile + "' in " +
                                                 parent().name() + '/' + name());
            }

            return credentials().thenApply(credentials -> {
                try {
                    return parseMirror(mirrorFile, entry, credentials);
                } catch (Exception e) {
                    throw new RepositoryMetadataException("failed to load the credential configuration", e);
                }
            });
        });
    }

    @Override
    public CompletableFuture<Revision> saveMirror(MirrorDto mirrorDto, Author author) {
        validateMirror(mirrorDto);
        final String summary;
        if (MirrorDirection.valueOf(mirrorDto.direction()) == MirrorDirection.REMOTE_TO_LOCAL) {
            summary = "Create a new mirror from " + mirrorDto.remoteUrl() + mirrorDto.remotePath() + '#' +
                      mirrorDto.remoteBranch() + " into " + mirrorDto.localRepo() + mirrorDto.localPath();
        } else {
            summary = "Create a new mirror from " + mirrorDto.localRepo() + mirrorDto.localPath() + " into " +
                      mirrorDto.remoteUrl() + mirrorDto.remotePath() + '#' + mirrorDto.remoteBranch();
        }
        return commitMirror(mirrorDto, author, summary);
    }

    @Override
    public CompletableFuture<Revision> updateMirror(MirrorDto mirrorDto, Author author) {
        validateMirror(mirrorDto);
        final String summary = "Update the mirror '" + mirrorDto.id() + '\'';
        return commitMirror(mirrorDto, author, summary);
    }

    private CompletableFuture<Revision> commitMirror(MirrorDto mirrorDto, Author author, String summary) {
        final MirrorConfig mirrorConfig = converterToMirrorConfig(mirrorDto);
        final JsonNode jsonNode = Jackson.valueToTree(mirrorConfig);
        final Change<JsonNode> change = Change.ofJsonUpsert(mirrorFile(mirrorConfig.id()), jsonNode);
        return commit(Revision.HEAD, System.currentTimeMillis(), author, summary, change)
                .thenApply(CommitResult::revision);
    }

    private static void validateMirror(MirrorDto mirror) {
        checkArgument(!Strings.isNullOrEmpty(mirror.id()), "Mirror ID is empty");
        final Cron schedule = MirrorConfig.CRON_PARSER.parse(mirror.schedule());
        final CronField secondField = schedule.retrieve(CronFieldName.SECOND);
        checkArgument(!secondField.getExpression().asString().contains("*"),
                      "The second field of the schedule must be specified. (seconds: *, expected: 0-59)");
    }

    private static MirrorConfig converterToMirrorConfig(MirrorDto mirrorDto) {
        final String remoteUri =
                mirrorDto.remoteScheme() + "://" + mirrorDto.remoteUrl() +
                MirrorUtil.normalizePath(mirrorDto.remotePath()) + '#' + mirrorDto.remoteBranch();

        return new MirrorConfig(
                mirrorDto.id(),
                mirrorDto.enabled(),
                mirrorDto.schedule(),
                MirrorDirection.valueOf(mirrorDto.direction()),
                mirrorDto.localRepo(),
                mirrorDto.localPath(),
                URI.create(remoteUri),
                mirrorDto.gitignore(),
                mirrorDto.credentialId());
    }

    private CompletableFuture<List<Mirror>> allMirrors() {
        return find(PATH_MIRRORS + "*.json").thenCompose(entries -> {
            if (entries.isEmpty()) {
                return UnmodifiableFuture.completedFuture(ImmutableList.of());
            }

            return credentials().thenApply(credentials -> {
                try {
                    return parseMirrors(entries, credentials);
                } catch (JsonProcessingException e) {
                    return Exceptions.throwUnsafely(e);
                }
            });
        });
    }

    private List<Mirror> parseMirrors(Map<String, Entry<?>> entries, List<MirrorCredential> credentials)
            throws JsonProcessingException {

        return entries.entrySet().stream().map(entry -> {
                          final JsonNode mirrorJson = (JsonNode) entry.getValue().content();
                          if (!mirrorJson.isObject()) {
                              throw newInvalidJsonTypeException(entry.getKey(), mirrorJson);
                          }
                          final MirrorConfig c;
                          try {
                              c = Jackson.treeToValue(mirrorJson, MirrorConfig.class);
                          } catch (JsonProcessingException e) {
                              return Exceptions.throwUnsafely(e);
                          }
                          return c.toMirror(parent(), credentials);
                      })
                      .filter(Objects::nonNull)
                      .collect(toImmutableList());
    }

    private Mirror parseMirror(String fileName, Entry<?> entry, List<MirrorCredential> credentials)
            throws JsonProcessingException {
        final JsonNode mirrorJson = (JsonNode) entry.content();
        if (!mirrorJson.isObject()) {
            throw newInvalidJsonTypeException(fileName, mirrorJson);
        }
        final MirrorConfig c;
        try {
            c = Jackson.treeToValue(mirrorJson, MirrorConfig.class);
        } catch (JsonProcessingException e) {
            return Exceptions.throwUnsafely(e);
        }
        final Mirror mirror = c.toMirror(parent(), credentials);
        if (mirror == null) {
            throw new EntryNotFoundException("failed to find a mirror config for '" + fileName + "' in " +
                                             parent().name() + '/' + name());
        }
        return mirror;
    }

    @Override
    public CompletableFuture<List<MirrorCredential>> credentials() {
        return find(PATH_CREDENTIALS + "*.json").thenApply(entries -> {
            if (entries.isEmpty()) {
                return ImmutableList.of();
            }
            try {
                return parseCredentials(entries);
            } catch (Exception e) {
                throw new RepositoryMetadataException("failed to load the credential configuration", e);
            }
        });
    }

    @Override
    public CompletableFuture<MirrorCredential> credential(String credentialId) {
        final String credentialFile = credentialFile(credentialId);
        return find(credentialFile).thenApply(entries -> {
            @SuppressWarnings("unchecked")
            final Entry<JsonNode> entry = (Entry<JsonNode>) entries.get(credentialFile);
            if (entry == null) {
                throw new EntryNotFoundException("failed to find credential '" + credentialId + "' in " +
                                                 parent().name() + '/' + name());
            }

            try {
                return parseCredential(credentialFile, entry);
            } catch (Exception e) {
                throw new RepositoryMetadataException("failed to load the credential configuration", e);
            }
        });
    }

    @Override
    public CompletableFuture<Revision> saveCredential(MirrorCredential credential, Author author) {
        checkArgument(!credential.id().isEmpty(), "Credential ID should not be empty");

        final String summary = "Create a new mirror credential for " + credential.id();
        return commitCredential(credential, author, summary);
    }

    @Override
    public CompletableFuture<Revision> updateCredential(MirrorCredential credential, Author author) {
        checkArgument(!credential.id().isEmpty(), "Credential ID should not be empty");

        final String summary = "Update the mirror credential '" + credential.id() + '\'';
        return commitCredential(credential, author, summary);
    }

    private CompletableFuture<Revision> commitCredential(MirrorCredential credential, Author author,
                                                         String summary) {
        final ObjectNode jsonNode = Jackson.valueToTree(credential);
        final Change<JsonNode> change = Change.ofJsonUpsert(credentialFile(credential.id()), jsonNode);
        return commit(Revision.HEAD, System.currentTimeMillis(), author, summary, change)
                .thenApply(CommitResult::revision);
    }

    private List<MirrorCredential> parseCredentials(Map<String, Entry<?>> entries)
            throws JsonProcessingException {
        return entries.entrySet().stream()
                      .map(entry -> {
                          try {
                              //noinspection unchecked
                              return parseCredential(entry.getKey(), (Entry<JsonNode>) entry.getValue());
                          } catch (JsonProcessingException e) {
                              return Exceptions.throwUnsafely(e);
                          }
                      })
                      .collect(toImmutableList());
    }

    private MirrorCredential parseCredential(String credentialFile, Entry<JsonNode> entry)
            throws JsonProcessingException {
        final JsonNode credentialJson = entry.content();
        if (!credentialJson.isObject()) {
            throw newInvalidJsonTypeException(credentialFile, credentialJson);
        }
        return Jackson.treeToValue(credentialJson, MirrorCredential.class);
    }

    private RepositoryMetadataException newInvalidJsonTypeException(String fileName, JsonNode credentialJson) {
        return new RepositoryMetadataException(parent().name() + '/' + name() + fileName +
                                               " must be an object: " + credentialJson.getNodeType());
    }

    private CompletableFuture<Map<String, Entry<?>>> find(String filePattern) {
        return find(Revision.HEAD, filePattern, ImmutableMap.of());
    }
}
