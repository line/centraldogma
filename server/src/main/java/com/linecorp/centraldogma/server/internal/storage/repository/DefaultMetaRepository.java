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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.centraldogma.internal.Util.requireNonNullElements;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.mirror.Mirror;
import com.linecorp.centraldogma.server.internal.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.internal.mirror.credential.MirrorCredential;
import com.linecorp.centraldogma.server.internal.storage.project.Project;

public class DefaultMetaRepository extends RepositoryWrapper implements MetaRepository {

    @VisibleForTesting
    static final String PATH_CREDENTIALS = "/credentials.json";

    @VisibleForTesting
    static final String PATH_MIRRORS = "/mirrors.json";

    private static final String PATH_CREDENTIALS_AND_MIRRORS = PATH_CREDENTIALS + ',' + PATH_MIRRORS;

    private final Lock mirrorLock = new ReentrantLock();

    /**
     * The revision number of the /credentials.json and /mirrors.json who generated {@link #mirrors}.
     */
    private int mirrorRev = -1;

    /**
     * The repositories of the parent {@link Project} at the moment when {@link #mirrors} is generated.
     */
    private Set<String> mirrorRepos = Collections.emptySet();

    private Set<Mirror> mirrors;

    public DefaultMetaRepository(Repository repo) {
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

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @Type(value = SingleMirrorConfig.class, name = "single"),
            @Type(value = MultipleMirrorConfig.class, name = "multiple")
    })
    private abstract static class MirrorConfig {

        static final String DEFAULT_SCHEDULE = "0 * * * * ?"; // Every minute

        static final CronParser cronParser = new CronParser(
                CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

        abstract List<Mirror> toMirrors(Project parent, Iterable<MirrorCredential> credentials);

        static MirrorCredential findCredential(Iterable<MirrorCredential> credentials, URI remoteUri) {
            MirrorCredential credential = MirrorCredential.FALLBACK;
            for (MirrorCredential c : credentials) {
                if (c.matches(remoteUri)) {
                    credential = c;
                    break;
                }
            }

            return credential;
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
        final Cron schedule;

        @JsonCreator
        SingleMirrorConfig(@JsonProperty("enabled") Boolean enabled,
                           @JsonProperty("schedule") String schedule,
                           @JsonProperty(value = "direction", required = true) MirrorDirection direction,
                           @JsonProperty(value = "localRepo", required = true) String localRepo,
                           @JsonProperty("localPath") String localPath,
                           @JsonProperty(value = "remoteUri", required = true) URI remoteUri) {

            super(firstNonNull(enabled, true));
            this.schedule = cronParser.parse(firstNonNull(schedule, DEFAULT_SCHEDULE));
            this.direction = requireNonNull(direction, "direction");
            this.localRepo = requireNonNull(localRepo, "localRepo");
            this.localPath = firstNonNull(localPath, "/");
            this.remoteUri = requireNonNull(remoteUri, "remoteUri");
        }

        @Override
        List<Mirror> toMirrors(Project parent, Iterable<MirrorCredential> credentials) {
            if (!enabled || localRepo == null || !parent.repos().exists(localRepo)) {
                return Collections.emptyList();
            }

            return Collections.singletonList(Mirror.of(
                    schedule, direction, findCredential(credentials, remoteUri),
                    parent.repos().get(localRepo), localPath, remoteUri));
        }
    }

    private static final class MultipleMirrorConfig extends MirrorConfig {

        final MirrorDirection defaultDirection;
        final String defaultLocalPath;
        final Cron defaultSchedule;
        final List<MirrorInclude> includes;
        final List<Pattern> excludes;

        @JsonCreator
        MultipleMirrorConfig(
                @JsonProperty("enabled") Boolean enabled,
                @JsonProperty("defaultSchedule") String defaultSchedule,
                @JsonProperty(value = "defaultDirection", required = true) MirrorDirection defaultDirection,
                @JsonProperty("defaultLocalPath") String defaultLocalPath,
                @JsonProperty(value = "includes", required = true)
                @JsonDeserialize(contentAs = MirrorInclude.class)
                Iterable<MirrorInclude> includes,
                @JsonProperty("excludes")
                @JsonDeserialize(contentAs = Pattern.class)
                Iterable<Pattern> excludes) {

            super(firstNonNull(enabled, true));
            this.defaultSchedule = cronParser.parse(firstNonNull(defaultSchedule, DEFAULT_SCHEDULE));
            this.defaultDirection = requireNonNull(defaultDirection, "defaultDirection");
            this.defaultLocalPath = firstNonNull(defaultLocalPath, "/");
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
                    builder.add(Mirror.of(firstNonNull(i.schedule, defaultSchedule),
                                          firstNonNull(i.direction, defaultDirection),
                                          findCredential(credentials, remoteUri),
                                          repo,
                                          firstNonNull(i.localPath, defaultLocalPath),
                                          remoteUri));
                }
            });

            return builder.build();
        }
    }

    private static final class MirrorInclude {

        final Pattern pattern;
        final String replacement;
        final MirrorDirection direction;
        final String localPath;
        final Cron schedule;

        @JsonCreator
        MirrorInclude(@JsonProperty("schedule") String schedule,
                      @JsonProperty(value = "pattern", required = true) Pattern pattern,
                      @JsonProperty(value = "replacement", required = true) String replacement,
                      @JsonProperty("direction") MirrorDirection direction,
                      @JsonProperty("localPath") String localPath) {

            this.schedule = schedule != null ? MirrorConfig.cronParser.parse(schedule) : null;
            this.pattern = requireNonNull(pattern, "pattern");
            this.replacement = requireNonNull(replacement, "replacement");
            this.direction = direction;
            this.localPath = localPath;
        }
    }
}
