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

import javax.annotation.Nullable;

import com.cronutils.model.Cron;
import com.cronutils.model.field.CronField;
import com.cronutils.model.field.CronFieldName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.MirrorRequest;
import com.linecorp.centraldogma.server.ZoneConfig;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirrorUtil;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;

public final class DefaultMetaRepository extends RepositoryWrapper implements MetaRepository {

    private static final String PATH_CREDENTIALS = "/credentials/";

    private static final String PATH_MIRRORS = "/mirrors/";

    public static boolean isMetaFile(String path) {
        return "/mirrors.json".equals(path) || "/credentials.json".equals(path) ||
               (path.endsWith(".json") && (path.startsWith(PATH_CREDENTIALS) || path.startsWith(PATH_MIRRORS)));
    }

    public static boolean isMirrorFile(String path) {
        return path.endsWith(".json") && (path.startsWith(PATH_CREDENTIALS) || path.startsWith(PATH_MIRRORS));
    }

    public static String credentialFile(String credentialId) {
        return PATH_CREDENTIALS + credentialId + ".json";
    }

    public static String credentialFile(String repoName, String credentialId) {
        return "/repos/" + repoName + credentialFile(credentialId);
    }

    public static String mirrorFile(String mirrorId) {
        return PATH_MIRRORS + mirrorId + ".json";
    }

    public DefaultMetaRepository(Repository repo) {
        super(repo);
    }

    @Override
    public org.eclipse.jgit.lib.Repository jGitRepository() {
        return unwrap().jGitRepository();
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
    public CompletableFuture<Mirror> mirror(String id, Revision revision) {
        final String mirrorFile = mirrorFile(id);
        return find(revision, mirrorFile).thenCompose(entries -> {
            @SuppressWarnings("unchecked")
            final Entry<JsonNode> entry = (Entry<JsonNode>) entries.get(mirrorFile);
            if (entry == null) {
                throw new EntryNotFoundException(
                        "failed to find mirror '" + mirrorFile + "' in " + parent().name() + '/' + name() +
                        " (revision: " + revision + ')');
            }

            final JsonNode mirrorJson = entry.content();
            if (!mirrorJson.isObject()) {
                throw newInvalidJsonTypeException(mirrorFile, mirrorJson);
            }
            final MirrorConfig c;
            try {
                c = Jackson.treeToValue(mirrorJson, MirrorConfig.class);
            } catch (JsonProcessingException e) {
                throw new RepositoryMetadataException("failed to load the mirror configuration", e);
            }

            final CompletableFuture<List<Credential>> credentials;
            if (Strings.isNullOrEmpty(c.credentialId())) {
                credentials = projectCredentials();
            } else {
                credentials = projectCredential(c.credentialId()).thenApply(ImmutableList::of);
            }
            return credentials.thenApply(credentials0 -> {
                final Mirror mirror = c.toMirror(parent(), credentials0);
                if (mirror == null) {
                    throw new EntryNotFoundException(
                            "failed to find a mirror config for '" + mirrorFile + "' in " +
                            parent().name() + '/' + name() + " (revision: " + revision + ')');
                }
                return mirror;
            });
        });
    }

    private CompletableFuture<List<Mirror>> allMirrors() {
        return find(PATH_MIRRORS + "*.json").thenCompose(entries -> {
            if (entries.isEmpty()) {
                return UnmodifiableFuture.completedFuture(ImmutableList.of());
            }

            return projectCredentials().thenApply(credentials -> {
                try {
                    return parseMirrors(entries, credentials);
                } catch (JsonProcessingException e) {
                    return Exceptions.throwUnsafely(e);
                }
            });
        });
    }

    private List<Mirror> parseMirrors(Map<String, Entry<?>> entries, List<Credential> credentials)
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

    @Override
    public CompletableFuture<List<Credential>> projectCredentials() {
        return find(PATH_CREDENTIALS + "*.json").thenApply(entries -> credentials(entries, null));
    }

    @Override
    public CompletableFuture<List<Credential>> repoCredentials(String repoName) {
        return find("/repos/" + repoName + PATH_CREDENTIALS + "*.json").thenApply(
                entries -> credentials(entries, repoName));
    }

    private List<Credential> credentials(Map<String, Entry<?>> entries, @Nullable String repoName) {
        if (entries.isEmpty()) {
            return ImmutableList.of();
        }
        try {
            return parseCredentials(entries);
        } catch (Exception e) {
            String message = "failed to load the credential configuration";
            if (repoName != null) {
                message += " for " + repoName;
            }
            throw new RepositoryMetadataException(message, e);
        }
    }

    @Override
    public CompletableFuture<Credential> projectCredential(String credentialId) {
        final String credentialFile = credentialFile(credentialId);
        return credential0(credentialFile);
    }

    @Override
    public CompletableFuture<Credential> repoCredential(String repoName, String id) {
        final String credentialFile = credentialFile(repoName, id);
        return credential0(credentialFile);
    }

    private CompletableFuture<Credential> credential0(String credentialFile) {
        return find(credentialFile).thenApply(entries -> {
            @SuppressWarnings("unchecked")
            final Entry<JsonNode> entry = (Entry<JsonNode>) entries.get(credentialFile);
            if (entry == null) {
                throw new EntryNotFoundException("failed to find credential file '" + credentialFile + "' in " +
                                                 parent().name() + '/' + name());
            }

            try {
                return parseCredential(credentialFile, entry);
            } catch (Exception e) {
                throw new RepositoryMetadataException(
                        "failed to load the credential configuration. credential file: " + credentialFile, e);
            }
        });
    }

    private List<Credential> parseCredentials(Map<String, Entry<?>> entries)
            throws JsonProcessingException {
        return entries.entrySet().stream()
                      .map(entry -> {
                          try {
                              //noinspection unchecked
                              return parseCredential(entry.getKey(),
                                                     (Entry<JsonNode>) entry.getValue());
                          } catch (JsonProcessingException e) {
                              return Exceptions.throwUnsafely(e);
                          }
                      })
                      .collect(toImmutableList());
    }

    private Credential parseCredential(String credentialFile, Entry<JsonNode> entry)
            throws JsonProcessingException {
        final JsonNode credentialJson = entry.content();
        if (!credentialJson.isObject()) {
            throw newInvalidJsonTypeException(credentialFile, credentialJson);
        }
        return Jackson.treeToValue(credentialJson, Credential.class);
    }

    private RepositoryMetadataException newInvalidJsonTypeException(
            String fileName, JsonNode credentialJson) {
        return new RepositoryMetadataException(parent().name() + '/' + name() + fileName +
                                               " must be an object: " + credentialJson.getNodeType());
    }

    private CompletableFuture<Map<String, Entry<?>>> find(String filePattern) {
        return find(Revision.HEAD, filePattern, ImmutableMap.of());
    }

    @Override
    public CompletableFuture<Command<CommitResult>> createMirrorPushCommand(
            MirrorRequest mirrorRequest, Author author,
            @Nullable ZoneConfig zoneConfig, boolean update) {
        validateMirror(mirrorRequest, zoneConfig);
        if (update) {
            final String summary = "Update the mirror '" + mirrorRequest.id() + '\'';
            return mirror(mirrorRequest.id()).thenApply(mirror -> {
                // Perform the update operation only if the mirror exists.
                return newMirrorCommand(mirrorRequest, author, summary);
            });
        } else {
            String summary = "Create a new mirror from " + mirrorRequest.remoteUrl() +
                             mirrorRequest.remotePath() + '#' + mirrorRequest.remoteBranch() + " into " +
                             mirrorRequest.localRepo() + mirrorRequest.localPath();
            if (MirrorDirection.valueOf(mirrorRequest.direction()) == MirrorDirection.REMOTE_TO_LOCAL) {
                summary = "[Remote-to-local] " + summary;
            } else {
                summary = "[Local-to-remote] " + summary;
            }
            return UnmodifiableFuture.completedFuture(newMirrorCommand(mirrorRequest, author, summary));
        }
    }

    @Override
    public CompletableFuture<Command<CommitResult>> createCredentialPushCommand(Credential credential,
                                                                                Author author, boolean update) {
        checkArgument(!credential.id().isEmpty(), "Credential ID should not be empty");

        if (update) {
            return projectCredential(credential.id()).thenApply(c -> {
                final String summary = "Update the mirror credential '" + credential.id() + '\'';
                return newCredentialCommand(credentialFile(credential.id()), credential, author, summary);
            });
        }
        final String summary = "Create a new mirror credential for " + credential.id();
        return UnmodifiableFuture.completedFuture(
                newCredentialCommand(credentialFile(credential.id()), credential, author, summary));
    }

    @Override
    public CompletableFuture<Command<CommitResult>> createCredentialPushCommand(String repoName,
                                                                                Credential credential,
                                                                                Author author, boolean update) {
        checkArgument(!credential.id().isEmpty(), "Credential ID should not be empty");

        if (update) {
            return repoCredential(repoName, credential.id()).thenApply(c -> {
                final String summary =
                        "Update the mirror credential '" + repoName + '/' + credential.id() + '\'';
                return newCredentialCommand(
                        credentialFile(repoName, credential.id()), credential, author, summary);
            });
        }
        final String summary = "Create a new mirror credential for " + repoName + '/' + credential.id();
        return UnmodifiableFuture.completedFuture(
                newCredentialCommand(credentialFile(repoName, credential.id()), credential, author, summary));
    }

    private Command<CommitResult> newCredentialCommand(String credentialFile, Credential credential,
                                                       Author author, String summary) {
        final JsonNode jsonNode = Jackson.valueToTree(credential);
        final Change<JsonNode> change = Change.ofJsonUpsert(credentialFile, jsonNode);
        return Command.push(author, parent().name(), name(), Revision.HEAD, summary, "", Markup.PLAINTEXT,
                            change);
    }

    private Command<CommitResult> newMirrorCommand(MirrorRequest mirrorRequest, Author author, String summary) {
        final MirrorConfig mirrorConfig = converterToMirrorConfig(mirrorRequest);
        final JsonNode jsonNode = Jackson.valueToTree(mirrorConfig);
        final Change<JsonNode> change = Change.ofJsonUpsert(mirrorFile(mirrorConfig.id()), jsonNode);
        return Command.push(author, parent().name(), name(), Revision.HEAD, summary, "", Markup.PLAINTEXT,
                            change);
    }

    private static void validateMirror(MirrorRequest mirror, @Nullable ZoneConfig zoneConfig) {
        checkArgument(!Strings.isNullOrEmpty(mirror.id()), "Mirror ID is empty");
        final String scheduleString = mirror.schedule();
        if (scheduleString != null) {
            final Cron schedule = MirrorConfig.CRON_PARSER.parse(scheduleString);
            final CronField secondField = schedule.retrieve(CronFieldName.SECOND);
            checkArgument(!secondField.getExpression().asString().contains("*"),
                          "The second field of the schedule must be specified. (seconds: *, expected: 0-59)");
        }

        final String zone = mirror.zone();
        if (zone != null) {
            checkArgument(zoneConfig != null, "Zone configuration is missing");
            checkArgument(zoneConfig.allZones().contains(zone),
                          "The zone '%s' is not in the zone configuration: %s", zone, zoneConfig);
        }
    }

    private static MirrorConfig converterToMirrorConfig(MirrorRequest mirrorDto) {
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
                mirrorDto.credentialId(),
                mirrorDto.zone());
    }
}
