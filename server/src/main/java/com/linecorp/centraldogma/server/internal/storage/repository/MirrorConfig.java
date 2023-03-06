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
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Optional;

import javax.annotation.Nullable;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Streams;

import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorCredential;
import com.linecorp.centraldogma.server.mirror.MirrorDirection;
import com.linecorp.centraldogma.server.storage.project.Project;

@JsonInclude(Include.NON_NULL)
public final class MirrorConfig {

    private static final String DEFAULT_SCHEDULE = "0 * * * * ?"; // Every minute

    public static final CronParser cronParser = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    private static MirrorCredential findCredential(Iterable<MirrorCredential> credentials, URI remoteUri,
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

    @Nullable
    private final String id;
    private final boolean enabled;
    private final MirrorDirection direction;
    @Nullable
    private final String localRepo;
    private final String localPath;
    private final URI remoteUri;
    @Nullable
    private final String gitignore;
    @Nullable
    private final String credentialId;
    private final Cron schedule;

    @JsonCreator
    public MirrorConfig(@JsonProperty("id") @Nullable String id,
                        @JsonProperty("enabled") @Nullable Boolean enabled,
                        @JsonProperty("schedule") @Nullable String schedule,
                        @JsonProperty(value = "direction", required = true) MirrorDirection direction,
                        @JsonProperty(value = "localRepo", required = true) String localRepo,
                        @JsonProperty("localPath") @Nullable String localPath,
                        @JsonProperty(value = "remoteUri", required = true) URI remoteUri,
                        @JsonProperty("gitignore") @Nullable Object gitignore,
                        @JsonProperty("credentialId") @Nullable String credentialId) {

        this.id = id;
        this.enabled = firstNonNull(enabled, true);
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

    @Nullable
    Mirror toMirror(Project parent, Iterable<MirrorCredential> credentials, int index) {
        if (localRepo == null || !parent.repos().exists(localRepo)) {
            return null;
        }

        return Mirror.of(index, id, schedule, direction, findCredential(credentials, remoteUri, credentialId),
                         parent.repos().get(localRepo), localPath, remoteUri, gitignore, enabled);
    }

    @JsonProperty("id")
    @Nullable
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

    @Nullable
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

    @Nullable
    @JsonProperty("credentialId")
    public String credentialId() {
        return credentialId;
    }

    @JsonProperty("schedule")
    public String schedule() {
        return schedule.asString();
    }
}
