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
import static com.linecorp.centraldogma.server.mirror.MirrorSchemes.SCHEME_DOGMA;
import static java.util.Objects.requireNonNull;

import java.net.URI;

import javax.annotation.Nullable;

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
import com.google.common.collect.Streams;

import com.linecorp.centraldogma.server.mirror.MirrorDirection;

// ignoreUnknown = true for backward compatibility since `type` field is removed.
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public final class MirrorConfig {

    public static final String DEFAULT_SCHEDULE = "0 * * * * ?"; // Every minute

    public static final CronParser CRON_PARSER = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    private final String id;
    private final boolean enabled;
    private final MirrorDirection direction;
    private final String localRepo;
    private final String localPath;
    private final URI remoteUri;
    @Nullable
    private final String gitignore;
    private final String credentialName;
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
                        // TODO(minwoox): Remove this credentialId property after migration is done.
                        @JsonProperty("credentialId") @Nullable String credentialId,
                        @JsonProperty("credentialName") @Nullable String credentialName,
                        @JsonProperty("zone") @Nullable String zone) {
        this(id, enabled, schedule != null ? CRON_PARSER.parse(schedule) : null, direction, localRepo,
             localPath, remoteUri, gitignore,
             requireNonNull(firstNonNull(credentialName, credentialId), "credentialName"),
             zone);
    }

    private MirrorConfig(String id, @Nullable Boolean enabled, @Nullable Cron schedule,
                         MirrorDirection direction, String localRepo, @Nullable String localPath,
                         URI remoteUri, @Nullable Object gitignore, String credentialName,
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
        this.credentialName = requireNonNull(credentialName, "credentialName");
        this.zone = zone;
    }

    public MirrorConfig withCredentialName(String credentialName) {
        return new MirrorConfig(id, enabled, schedule, direction, localRepo, localPath, remoteUri,
                                gitignore, credentialName, zone);
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

    URI rawRemoteUri() {
        return remoteUri;
    }

    @JsonProperty("gitignore")
    @Nullable
    public String gitignore() {
        return gitignore;
    }

    @JsonProperty("credentialName")
    public String credentialName() {
        return credentialName;
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
    Cron cronSchedule() {
        return schedule;
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
                          .add("credentialName", credentialName)
                          .add("schedule", schedule)
                          .add("zone", zone)
                          .toString();
    }
}
