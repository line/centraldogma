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
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    private static final Pattern MIRROR_PATH_PATTERN = Pattern.compile("/repos/[^/]+/mirrors/[^/]+\\.json");

    private static final Pattern REPO_CREDENTIAL_PATH_PATTERN =
            Pattern.compile("/repos/[^/]+/credentials/[^/]+\\.json");

    // Project level credentials
    public static final String PATH_CREDENTIALS = "/credentials/";

    public static final String LEGACY_MIRRORS_PATH = "/mirrors/";

    public static final String NEW_MIRRORS_PATH = "/repos/*/mirrors/";

    public static boolean isMetaFile(String path) {
        return "/mirrors.json".equals(path) || "/credentials.json".equals(path) ||
               (path.endsWith(".json") &&
                (path.startsWith(PATH_CREDENTIALS) || path.startsWith(LEGACY_MIRRORS_PATH))) ||
               isMirrorOrCredentialFile(path);
    }

    public static boolean isMirrorOrCredentialFile(String path) {
        return MIRROR_PATH_PATTERN.matcher(path).matches() ||
               REPO_CREDENTIAL_PATH_PATTERN.matcher(path).matches();
    }

    public static String mirrorFile(String repoName, String mirrorId) {
        return "/repos/" + repoName + "/mirrors/" + mirrorId + ".json";
    }

    public static String credentialFile(String credentialId) {
        return PATH_CREDENTIALS + credentialId + ".json";
    }

    public static String credentialFile(String repoName, String credentialId) {
        return "/repos/" + repoName + credentialFile(credentialId);
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
        final CompletableFuture<List<Mirror>> future = allMirrors();
        return maybeFilter(future, includeDisabled);
    }

    @Override
    public CompletableFuture<List<Mirror>> mirrors(String repoName, boolean includeDisabled) {
        final CompletableFuture<List<Mirror>> future = allMirrors(repoName);
        return maybeFilter(future, includeDisabled);
    }

    private static CompletableFuture<List<Mirror>> maybeFilter(CompletableFuture<List<Mirror>> future,
                                                               boolean includeDisabled) {
        if (includeDisabled) {
            return future;
        }
        return future.thenApply(mirrors -> mirrors.stream().filter(Mirror::enabled).collect(toImmutableList()));
    }

    @Override
    public CompletableFuture<Mirror> mirror(String repoName, String id, Revision revision) {
        final String mirrorFile = mirrorFile(repoName, id);
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

            if (Strings.isNullOrEmpty(c.credentialId())) {
                if (!parent().repos().exists(repoName)) {
                    throw mirrorNotFound(revision, mirrorFile);
                }
                return CompletableFuture.completedFuture(c.toMirror(parent(), Credential.FALLBACK));
            }

            return convert(ImmutableList.of(repoName), (repoCredentials, projectCredentials) -> {
                final Mirror mirror = c.toMirror(parent(), repoCredentials, projectCredentials);
                if (mirror == null) {
                    throw mirrorNotFound(revision, mirrorFile);
                }
                return mirror;
            });
        });
    }

    private EntryNotFoundException mirrorNotFound(Revision revision, String mirrorFile) {
        return new EntryNotFoundException(
                "failed to find a mirror config for '" + mirrorFile + "' in " +
                parent().name() + '/' + name() + " (revision: " + revision + ')');
    }

    private CompletableFuture<List<Mirror>> allMirrors() {
        return find("/repos/*/mirrors/*.json").thenCompose(entries -> {
            if (entries.isEmpty()) {
                return UnmodifiableFuture.completedFuture(ImmutableList.of());
            }

            final List<String> repoNames = entries.keySet().stream()
                                                  .map(path -> path.substring(7, path.indexOf('/', 8)))
                                                  .distinct()
                                                  .collect(toImmutableList());
            return convert(entries, repoNames);
        });
    }

    private CompletableFuture<List<Mirror>> allMirrors(String repoName) {
        return find("/repos/" + repoName + "/mirrors/*.json").thenCompose(entries -> {
            if (entries.isEmpty()) {
                return UnmodifiableFuture.completedFuture(ImmutableList.of());
            }

            return convert(entries, ImmutableList.of(repoName));
        });
    }

    private CompletableFuture<List<Mirror>> convert(
            Map<String, Entry<?>> entries, List<String> repoNames) {
        return convert(repoNames, (repoCredentials, projectCredentials) -> {
            try {
                return parseMirrors(entries, repoCredentials, projectCredentials);
            } catch (JsonProcessingException e) {
                return Exceptions.throwUnsafely(e);
            }
        });
    }

    private <T> CompletableFuture<T> convert(
            List<String> repoNames,
            BiFunction<Map<String, List<Credential>>, List<Credential>, T> convertFunction) {
        final ImmutableList.Builder<CompletableFuture<List<Credential>>> builder = ImmutableList.builder();

        for (String repoName : repoNames) {
            builder.add(repoCredentials(repoName));
        }
        final ImmutableList<CompletableFuture<List<Credential>>> futures = builder.build();
        final CompletableFuture<List<Credential>> projectCredentialsFuture = projectCredentials();

        final CompletableFuture<Void> allOfFuture =
                CompletableFuture.allOf(Stream.concat(futures.stream(),
                                                      Stream.of(projectCredentialsFuture))
                                              .toArray(CompletableFuture<?>[]::new));
        return allOfFuture.thenApply(unused -> {
            final ImmutableMap.Builder<String, List<Credential>> repoCredentialsBuilder =
                    ImmutableMap.builder();
            for (int i = 0; i < repoNames.size(); i++) {
                repoCredentialsBuilder.put(repoNames.get(i), futures.get(i).join());
            }
            final List<Credential> projectCredentials = projectCredentialsFuture.join();
            return convertFunction.apply(repoCredentialsBuilder.build(), projectCredentials);
        });
    }

    private List<Mirror> parseMirrors(Map<String, Entry<?>> entries,
                                      Map<String, List<Credential>> repoCredentials,
                                      List<Credential> projectCredentials)
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
                          return c.toMirror(parent(), repoCredentials, projectCredentials);
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
            MirrorRequest mirrorRequest, Author author, @Nullable ZoneConfig zoneConfig, boolean update) {
        final String repoName = mirrorRequest.localRepo();
        validateMirror(mirrorRequest, zoneConfig);
        if (update) {
            final String summary = "Update the mirror '" + mirrorRequest.id() + "' in " + repoName;
            return mirror(repoName, mirrorRequest.id()).thenApply(mirror -> {
                return newMirrorCommand(mirrorRequest, author, summary);
            });
        } else {
            String summary = "Create a new mirror from " + mirrorRequest.remoteUrl() +
                             mirrorRequest.remotePath() + '#' + mirrorRequest.remoteBranch() + " into " +
                             repoName + mirrorRequest.localPath();
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
        final Change<JsonNode> change =
                Change.ofJsonUpsert(mirrorFile(mirrorRequest.localRepo(), mirrorConfig.id()), jsonNode);
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
