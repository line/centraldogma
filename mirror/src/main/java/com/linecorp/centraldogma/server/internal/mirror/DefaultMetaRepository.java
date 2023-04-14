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

package com.linecorp.centraldogma.server.internal.mirror;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.centraldogma.internal.Util.requireNonNullElements;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_DOGMA;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_FILE;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_HTTP;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_HTTPS;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_GIT_SSH;
import static com.linecorp.centraldogma.server.mirror.MirrorUtil.split;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryMetadataException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryWrapper;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;

final class DefaultMetaRepository extends RepositoryWrapper implements MetaRepository {

    private static final String PATH_CREDENTIALS_AND_MIRRORS = PATH_CREDENTIALS + ',' + PATH_MIRRORS;

    private static final Pattern DOGMA_PATH_PATTERN = Pattern.compile("^/([^/]+)/([^/]+)\\.dogma$");

    private final Lock mirrorLock = new ReentrantLock();

    /**
     * The revision number of the /credentials.json and /mirrors.json who generated {@link #mirrors}.
     */
    private int mirrorRev = -1;

    /**
     * The repositories of the parent {@link Project} at the moment when {@link #mirrors} is generated.
     */
    private Set<String> mirrorRepos = Collections.emptySet();

    @Nullable
    private Set<Mirror> mirrors;

    DefaultMetaRepository(Repository repo) {
        super(repo);
    }

    @Override
    public Set<Mirror> mirrors() {
        mirrorLock.lock();
        try {
            final int headRev = normalizeNow(Revision.HEAD).major();
            final Set<String> repos = parent().repos().list().keySet();
            if (headRev > mirrorRev || !mirrorRepos.equals(repos)) {
                mirrors = loadMirrors(headRev);
                mirrorRev = headRev;
                mirrorRepos = repos;
            }

            return mirrors;
        } finally {
            mirrorLock.unlock();
        }
    }

    private Set<Mirror> loadMirrors(int rev) {
        // TODO(trustin): Asynchronization
        final Map<String, Entry<?>> entries =
                find(new Revision(rev), PATH_CREDENTIALS_AND_MIRRORS, Collections.emptyMap()).join();

        if (!entries.containsKey(PATH_MIRRORS)) {
            return Collections.emptySet();
        }

        final JsonNode mirrorsJson = (JsonNode) entries.get(PATH_MIRRORS).content();
        if (!mirrorsJson.isArray()) {
            throw new RepositoryMetadataException(
                    PATH_MIRRORS + " must be an array: " + mirrorsJson.getNodeType());
        }

        if (mirrorsJson.size() == 0) {
            return Collections.emptySet();
        }

        try {
            final List<MirrorCredential> credentials = loadCredentials(entries);
            final ImmutableSet.Builder<Mirror> mirrors = ImmutableSet.builder();

            for (JsonNode m : mirrorsJson) {
                final MirrorConfig c = Jackson.treeToValue(m, MirrorConfig.class);
                if (c == null) {
                    throw new RepositoryMetadataException(PATH_MIRRORS + " contains null.");
                }
                mirrors.addAll(c.toMirrors(parent(), credentials));
            }

            return mirrors.build();
        } catch (RepositoryMetadataException e) {
            throw e;
        } catch (Exception e) {
            throw new RepositoryMetadataException("failed to load the mirror configuration", e);
        }
    }

    private static List<MirrorCredential> loadCredentials(Map<String, Entry<?>> entries) throws Exception {
        final Entry<?> e = entries.get(PATH_CREDENTIALS);
        if (e == null) {
            return Collections.emptyList();
        }

        final JsonNode credentialsJson = (JsonNode) e.content();
        if (!credentialsJson.isArray()) {
            throw new RepositoryMetadataException(
                    PATH_CREDENTIALS + " must be an array: " + credentialsJson.getNodeType());
        }

        if (credentialsJson.size() == 0) {
            return Collections.emptyList();
        }

        final ImmutableList.Builder<MirrorCredential> builder = ImmutableList.builder();
        for (JsonNode c : credentialsJson) {
            final MirrorCredential credential = Jackson.treeToValue(c, MirrorCredential.class);
            if (credential == null) {
                throw new RepositoryMetadataException(PATH_CREDENTIALS + " contains null.");
            }
            builder.add(credential);
        }

        return builder.build();
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @Type(value = SingleMirrorConfig.class, name = "single"),
            @Type(value = MultipleMirrorConfig.class, name = "multiple")
    })
    private abstract static class MirrorConfig {

        static final String DEFAULT_SCHEDULE = "0 * * * * ?"; // Every minute

        static final CronParser cronParser = new CronParser(
                CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

        abstract List<Mirror> toMirrors(Project parent, Iterable<MirrorCredential> credentials);

        static MirrorCredential findCredential(Iterable<MirrorCredential> credentials, URI remoteUri,
                                               @Nullable String credentialId) {
            if (credentialId != null) {
                // Find by credential ID.
                for (MirrorCredential c : credentials) {
                    final Optional<String> id = c.id();
                    if (id.isPresent() && credentialId.equals(id.get())) {
                        return c;
                    }
                }
            } else {
                // Find by host name.
                for (MirrorCredential c : credentials) {
                    if (c.matches(remoteUri)) {
                        return c;
                    }
                }
            }

            return MirrorCredential.FALLBACK;
        }

        final boolean enabled;

        MirrorConfig(boolean enabled) {
            this.enabled = enabled;
        }
    }

    private static final class SingleMirrorConfig extends MirrorConfig {

        final MirrorDirection direction;
        final String localRepo;
        final String localPath;
        final URI remoteUri;
        @Nullable
        final String gitignore;
        @Nullable
        final String credentialId;
        final Cron schedule;

        @JsonCreator
        SingleMirrorConfig(@JsonProperty("enabled") @Nullable Boolean enabled,
                           @JsonProperty("schedule") @Nullable String schedule,
                           @JsonProperty(value = "direction", required = true) MirrorDirection direction,
                           @JsonProperty(value = "localRepo", required = true) String localRepo,
                           @JsonProperty("localPath") @Nullable String localPath,
                           @JsonProperty(value = "remoteUri", required = true) URI remoteUri,
                           @JsonProperty("gitignore") @Nullable Object gitignore,
                           @JsonProperty("credentialId") @Nullable String credentialId) {

            super(firstNonNull(enabled, true));
            this.schedule = cronParser.parse(firstNonNull(schedule, DEFAULT_SCHEDULE));
            this.direction = requireNonNull(direction, "direction");
            this.localRepo = requireNonNull(localRepo, "localRepo");
            this.localPath = firstNonNull(localPath, "/");
            this.remoteUri = requireNonNull(remoteUri, "remoteUri");
            if (gitignore != null) {
                if (gitignore instanceof Iterable &&
                    Streams.stream((Iterable<?>) gitignore).allMatch(String.class::isInstance)) {
                    this.gitignore = String.join("\n", (Iterable<String>) gitignore);
                } else if (gitignore instanceof String) {
                    this.gitignore = (String) gitignore;
                } else {
                    throw new IllegalArgumentException(
                            "gitignore: " + gitignore + " (expected: either a string or an array of strings)");
                }
            } else {
                this.gitignore = null;
            }
            this.credentialId = credentialId;
        }

        @Override
        List<Mirror> toMirrors(Project parent, Iterable<MirrorCredential> credentials) {
            if (!enabled || localRepo == null || !parent.repos().exists(localRepo)) {
                return Collections.emptyList();
            }

            return Collections.singletonList(newMirror(
                    schedule, direction, findCredential(credentials, remoteUri, credentialId),
                    parent.repos().get(localRepo), localPath, remoteUri, gitignore));
        }
    }

    static Mirror newMirror(Cron schedule, MirrorDirection direction, MirrorCredential credential,
                            Repository localRepo, String localPath, URI remoteUri,
                            @Nullable String gitignore) {
        requireNonNull(schedule, "schedule");
        requireNonNull(direction, "direction");
        requireNonNull(credential, "credential");
        requireNonNull(localRepo, "localRepo");
        requireNonNull(localPath, "localPath");
        requireNonNull(remoteUri, "remoteUri");

        final String scheme = remoteUri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("no scheme in remoteUri: " + remoteUri);
        }

        switch (scheme) {
            case SCHEME_DOGMA: {
                final String[] components = split(remoteUri, "dogma");
                final URI remoteRepoUri = URI.create(components[0]);
                final Matcher matcher = DOGMA_PATH_PATTERN.matcher(remoteRepoUri.getPath());
                if (!matcher.find()) {
                    throw new IllegalArgumentException(
                            "cannot determine project name and repository name: " + remoteUri +
                            " (expected: dogma://<host>[:<port>]/<project>/<repository>.dogma[<remotePath>])");
                }

                final String remoteProject = matcher.group(1);
                final String remoteRepo = matcher.group(2);

                return new CentralDogmaMirror(schedule, direction, credential, localRepo, localPath,
                                              remoteRepoUri, remoteProject, remoteRepo, components[1],
                                              gitignore);
            }
            case SCHEME_GIT:
            case SCHEME_GIT_SSH:
            case SCHEME_GIT_HTTP:
            case SCHEME_GIT_HTTPS:
            case SCHEME_GIT_FILE: {
                final String[] components = split(remoteUri, "git");
                return new GitMirror(schedule, direction, credential, localRepo, localPath,
                                     URI.create(components[0]), components[1], components[2],
                                     gitignore);
            }
        }

        throw new IllegalArgumentException("unsupported scheme in remoteUri: " + remoteUri);
    }

    private static final class MultipleMirrorConfig extends MirrorConfig {

        final MirrorDirection defaultDirection;
        final String defaultLocalPath;
        final Cron defaultSchedule;
        @Nullable
        final String defaultCredentialId;
        final List<MirrorInclude> includes;
        final List<Pattern> excludes;

        @JsonCreator
        MultipleMirrorConfig(
                @JsonProperty("enabled") @Nullable Boolean enabled,
                @JsonProperty("defaultSchedule") @Nullable String defaultSchedule,
                @JsonProperty(value = "defaultDirection", required = true) MirrorDirection defaultDirection,
                @JsonProperty("defaultLocalPath") @Nullable String defaultLocalPath,
                @JsonProperty("defaultCredentialId") @Nullable String defaultCredentialId,
                @JsonProperty(value = "includes", required = true)
                @JsonDeserialize(contentAs = MirrorInclude.class)
                Iterable<MirrorInclude> includes,
                @JsonProperty("excludes") @Nullable
                @JsonDeserialize(contentAs = Pattern.class)
                Iterable<Pattern> excludes) {

            super(firstNonNull(enabled, true));
            this.defaultSchedule = cronParser.parse(firstNonNull(defaultSchedule, DEFAULT_SCHEDULE));
            this.defaultDirection = requireNonNull(defaultDirection, "defaultDirection");
            this.defaultLocalPath = firstNonNull(defaultLocalPath, "/");
            this.defaultCredentialId = defaultCredentialId;
            this.includes = ImmutableList.copyOf(requireNonNullElements(includes, "includes"));
            if (excludes != null) {
                this.excludes = ImmutableList.copyOf(requireNonNullElements(excludes, "excludes"));
            } else {
                this.excludes = Collections.emptyList();
            }
        }

        @Override
        List<Mirror> toMirrors(Project parent, Iterable<MirrorCredential> credentials) {
            if (!enabled) {
                return Collections.emptyList();
            }

            final ImmutableList.Builder<Mirror> builder = ImmutableList.builder();
            parent.repos().list().forEach((repoName, repo) -> {
                if (repoName == null || excludes.stream().anyMatch(p -> p.matcher(repoName).find())) {
                    return;
                }

                for (MirrorInclude i : includes) {
                    final Matcher m = i.pattern.matcher(repoName);
                    if (!m.matches()) {
                        continue;
                    }

                    final URI remoteUri = URI.create(m.replaceFirst(i.replacement));
                    builder.add(newMirror(firstNonNull(i.schedule, defaultSchedule),
                                          firstNonNull(i.direction, defaultDirection),
                                          findCredential(credentials, remoteUri,
                                                         i.credentialId != null ? i.credentialId
                                                                                : defaultCredentialId),
                                          repo,
                                          firstNonNull(i.localPath, defaultLocalPath),
                                          remoteUri,
                                          null));
                }
            });

            return builder.build();
        }
    }

    private static final class MirrorInclude {

        final Pattern pattern;
        final String replacement;
        @Nullable
        final MirrorDirection direction;
        @Nullable
        final String localPath;
        @Nullable
        final String credentialId;
        @Nullable
        final Cron schedule;

        @JsonCreator
        MirrorInclude(@JsonProperty("schedule") @Nullable String schedule,
                      @JsonProperty(value = "pattern", required = true) Pattern pattern,
                      @JsonProperty(value = "replacement", required = true) String replacement,
                      @JsonProperty("direction") @Nullable MirrorDirection direction,
                      @JsonProperty("localPath") @Nullable String localPath,
                      @JsonProperty("credentialId") @Nullable String credentialId) {

            this.schedule = schedule != null ? MirrorConfig.cronParser.parse(schedule) : null;
            this.pattern = requireNonNull(pattern, "pattern");
            this.replacement = requireNonNull(replacement, "replacement");
            this.direction = direction;
            this.localPath = localPath;
            this.credentialId = credentialId;
        }
    }
}
