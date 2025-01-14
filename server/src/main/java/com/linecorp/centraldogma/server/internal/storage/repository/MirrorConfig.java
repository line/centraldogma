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
 *
 */

package com.linecorp.centraldogma.server.internal.storage.repository;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.internal.api.v1.MirrorRequest.PROJECT_CREDENTIAL_ID_PATTERN;
import static com.linecorp.centraldogma.internal.api.v1.MirrorRequest.REPO_CREDENTIAL_ID_PATTERN;
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_DOGMA;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.regex.Matcher;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorContext;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.mirror.MirrorProvider;
import com.linecorp.centraldogma.server.storage.project.Project;

// ignoreUnknown = true for backward compatibility since `type` field is removed.
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public final class MirrorConfig {

    private static final Logger logger = LoggerFactory.getLogger(MirrorConfig.class);

    public static final String DEFAULT_SCHEDULE = "0 * * * * ?"; // Every minute

    public static final CronParser CRON_PARSER = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    public static final List<MirrorProvider> MIRROR_PROVIDERS;

    static {
        MIRROR_PROVIDERS = ImmutableList.copyOf(ServiceLoader.load(MirrorProvider.class));
        logger.debug("Available {}s: {}", MirrorProvider.class.getSimpleName(), MIRROR_PROVIDERS);
    }

    private final String id;
    private final boolean enabled;
    private final MirrorDirection direction;
    private final String localRepo;
    private final String localPath;
    private final URI remoteUri;
    @Nullable
    private final String gitignore;
    private final String credentialId;
    private final boolean repoCredential;
    private final String credentialResourceId;
    @Nullable
    private final Cron schedule;
    @Nullable
    private final String zone;

    @JsonCreator
    public MirrorConfig(@JsonProperty("id") String id,
                        @JsonProperty("enabled") @Nullable Boolean enabled,
                        @JsonProperty("schedule") @Nullable String schedule,
                        @JsonProperty(value = "direction", required = true) MirrorDirection direction,
                        @JsonProperty(value = "localRepo", required = true) String localRepo,
                        @JsonProperty("localPath") @Nullable String localPath,
                        @JsonProperty(value = "remoteUri", required = true) URI remoteUri,
                        @JsonProperty("gitignore") @Nullable Object gitignore,
                        @JsonProperty("credentialId") String credentialId,
                        @JsonProperty("zone") @Nullable String zone) {
        this(id, enabled, schedule != null ? CRON_PARSER.parse(schedule) : null, direction, localRepo,
             localPath, remoteUri, gitignore, credentialId, zone);
    }

    private MirrorConfig(String id, @Nullable Boolean enabled, @Nullable Cron schedule,
                         MirrorDirection direction, String localRepo, @Nullable String localPath,
                         URI remoteUri, @Nullable Object gitignore, String credentialId,
                         @Nullable String zone) {
        this.id = requireNonNull(id, "id");
        this.enabled = firstNonNull(enabled, true);
        this.schedule = schedule;
        this.direction = requireNonNull(direction, "direction");
        this.localRepo = requireNonNull(localRepo, "localRepo");
        this.localPath = firstNonNull(localPath, "/");
        this.remoteUri = requireNonNull(remoteUri, "remoteUri");

        // Validate the remote URI.
        final String suffix = remoteUri.getScheme().equals(SCHEME_DOGMA) ? "dogma" : "git";
        RepositoryUri.parse(remoteUri, suffix);

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
        this.credentialId = requireNonNull(credentialId, "credentialId");
        if (isNullOrEmpty(credentialId)) {
            // Credential.FALLBACK
            repoCredential = false;
            credentialResourceId = "";
        } else {
            Matcher matcher = REPO_CREDENTIAL_ID_PATTERN.matcher(credentialId);
            if (matcher.matches()) {
                repoCredential = true;
                credentialResourceId = matcher.group(3);
            } else {
                matcher = PROJECT_CREDENTIAL_ID_PATTERN.matcher(credentialId);
                if (matcher.matches()) {
                    repoCredential = false;
                    credentialResourceId = matcher.group(2);
                } else {
                    // In the middle of migration from legacy credential to project-credential.
                    assert !credentialId.contains("/") : credentialId;
                    repoCredential = false;
                    credentialResourceId = credentialId;
                }
            }
        }

        this.zone = zone;
    }

    public MirrorConfig withCredentialId(String credentialId) {
        return new MirrorConfig(id, enabled, schedule, direction, localRepo, localPath, remoteUri,
                                gitignore, credentialId, zone);
    }

    @Nullable
    Mirror toMirror(Project parent, Map<String, List<Credential>> repoCredentials,
                    List<Credential> projectCredentials) {
        if (!parent.repos().exists(localRepo)) {
            return null;
        }

        final Credential credential = findCredential(repoCredentials, projectCredentials);
        return toMirror(parent, credential);
    }

    Mirror toMirror(Project parent, Credential credential) {
        final MirrorContext mirrorContext = new MirrorContext(
                id, enabled, schedule, direction,
                credential, credential == Credential.FALLBACK ? "" : credentialId,
                parent.repos().get(localRepo), localPath, remoteUri, gitignore, zone);
        for (MirrorProvider mirrorProvider : MIRROR_PROVIDERS) {
            final Mirror mirror = mirrorProvider.newMirror(mirrorContext);
            if (mirror != null) {
                return mirror;
            }
        }

        throw new IllegalArgumentException("could not find a mirror provider for " + mirrorContext);
    }

    private Credential findCredential(Map<String, List<Credential>> repoCredentials,
                                      List<Credential> projectCredentials) {
        if (repoCredential) {
            final List<Credential> credentials = repoCredentials.get(localRepo);
            if (credentials != null) {
                for (Credential c : credentials) {
                    final String id = c.id();
                    if (credentialResourceId.equals(id)) {
                        return c;
                    }
                }
            }
        } else {
            for (Credential c : projectCredentials) {
                final String id = c.id();
                if (credentialResourceId.equals(id)) {
                    return c;
                }
            }
        }

        return Credential.FALLBACK;
    }

    @JsonProperty("id")
    public String id() {
        return id;
    }

    @JsonProperty("enabled")
    public boolean enabled() {
        return enabled;
    }

    @JsonProperty("direction")
    public MirrorDirection direction() {
        return direction;
    }

    @JsonProperty("localRepo")
    public String localRepo() {
        return localRepo;
    }

    @JsonProperty("localPath")
    public String localPath() {
        return localPath;
    }

    @JsonProperty("remoteUri")
    public String remoteUri() {
        return remoteUri.toString();
    }

    @JsonProperty("gitignore")
    @Nullable
    public String gitignore() {
        return gitignore;
    }

    @JsonProperty("credentialId")
    public String credentialId() {
        return credentialId;
    }

    public boolean repoCredential() {
        return repoCredential;
    }

    @Nullable
    @JsonProperty("schedule")
    public String schedule() {
        if (schedule != null) {
            return schedule.asString();
        } else {
            return null;
        }
    }

    @Nullable
    @JsonProperty("zone")
    public String zone() {
        return zone;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("enabled", enabled)
                          .add("direction", direction)
                          .add("localRepo", localRepo)
                          .add("localPath", localPath)
                          .add("remoteUri", remoteUri)
                          .add("gitignore", gitignore)
                          .add("credentialId", credentialId)
                          .add("schedule", schedule)
                          .add("zone", zone)
                          .toString();
    }
}
