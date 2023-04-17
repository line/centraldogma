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
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.JacksonObjectMapperProvider;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorContext;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirrorProvider;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;
import com.linecorp.centraldogma.server.storage.repository.Repository;

public final class DefaultMetaRepository extends RepositoryWrapper implements MetaRepository {

    private static final Logger logger = LoggerFactory.getLogger(DefaultMetaRepository.class);

    public static final String PATH_CREDENTIALS = "/credentials.json";

    public static final String PATH_MIRRORS = "/mirrors.json";

    public static final Set<String> metaRepoFiles = ImmutableSet.of(PATH_CREDENTIALS, PATH_MIRRORS);

    private static final String PATH_CREDENTIALS_AND_MIRRORS = PATH_CREDENTIALS + ',' + PATH_MIRRORS;

    private static final MirrorProvider MIRROR_PROVIDER;

    static {
        final List<MirrorProvider> providers =
                ImmutableList.copyOf(ServiceLoader.load(MirrorProvider.class));
        if (!providers.isEmpty()) {
            MIRROR_PROVIDER = providers.get(0);
            // Consider allowing multiple providers depending on the scheme.
            if (providers.size() > 1) {
                logger.warn("Found {} {}s. The first provider found will be used among {}",
                            providers.size(), MirrorProvider.class.getSimpleName(), providers);
            } else {
                logger.info("Using {} as a {}",
                            MIRROR_PROVIDER.getClass().getSimpleName(),
                            JacksonObjectMapperProvider.class.getSimpleName());
            }
        } else {
            MIRROR_PROVIDER = LogNoMirrorProviderWarningOnce.INSTANCE;
        }
    }

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
                final Mirror mirror = c.toMirror(parent(), credentials);
                if (mirror != null) {
                    mirrors.add(mirror);
                }
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
    @JsonSubTypes(@Type(value = SingleMirrorConfig.class, name = "single"))
    private abstract static class MirrorConfig {

        static final String DEFAULT_SCHEDULE = "0 * * * * ?"; // Every minute

        static final CronParser cronParser = new CronParser(
                CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

        @Nullable
        abstract Mirror toMirror(Project parent, Iterable<MirrorCredential> credentials);

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
        Mirror toMirror(Project parent, Iterable<MirrorCredential> credentials) {
            if (!enabled || !parent.repos().exists(localRepo)) {
                return null;
            }
            final MirrorContext mirrorContext = new MirrorContext(
                    schedule, direction, findCredential(credentials, remoteUri, credentialId),
                    parent.repos().get(localRepo), localPath, remoteUri, gitignore);
            return MIRROR_PROVIDER.newMirror(mirrorContext);
        }
    }

    private enum LogNoMirrorProviderWarningOnce implements MirrorProvider {

        INSTANCE;

        @Override
        public Mirror newMirror(MirrorContext context) {
            ClassLoaderHack.loadMe();
            throw new UnsupportedOperationException();
        }

        private static final class ClassLoaderHack {
            static void loadMe() {}

            static {
                logger.warn("{} is not provided. Did you forget to add server-mirror-git module?",
                            MirrorProvider.class.getName());
            }
        }
    }
}
