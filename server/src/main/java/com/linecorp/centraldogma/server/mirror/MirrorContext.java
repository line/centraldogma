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

package com.linecorp.centraldogma.server.mirror;

import static java.util.Objects.requireNonNull;

import java.net.URI;

import javax.annotation.Nullable;

import com.cronutils.model.Cron;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.credential.Credential;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * A context of a mirror.
 */
public final class MirrorContext {

    private final String id;
    private final boolean enabled;
    @Nullable
    private final Cron schedule;
    private final MirrorDirection direction;
    private final Credential credential;
    private final Repository localRepo;
    private final String localPath;
    private final URI remoteUri;
    @Nullable
    private final String gitignore;
    @Nullable
    private final String zone;

    /**
     * Creates a new instance.
     */
    public MirrorContext(String id, boolean enabled, @Nullable Cron schedule, MirrorDirection direction,
                         Credential credential, Repository localRepo,
                         String localPath, URI remoteUri, @Nullable String gitignore, @Nullable String zone) {
        this.id = requireNonNull(id, "id");
        this.enabled = enabled;
        this.schedule = schedule;
        this.direction = requireNonNull(direction, "direction");
        this.credential = requireNonNull(credential, "credential");
        this.localRepo = requireNonNull(localRepo, "localRepo");
        this.localPath = requireNonNull(localPath, "localPath");
        this.remoteUri = requireNonNull(remoteUri, "remoteUri");
        this.gitignore = gitignore;
        this.zone = zone;
    }

    /**
     * Returns the ID of this mirror.
     */
    public String id() {
        return id;
    }

    /**
     * Returns whether this mirror is enabled or not.
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the cron schedule of this mirror.
     * {@code null} if this mirror is not scheduled automatically.
     */
    @Nullable
    public Cron schedule() {
        return schedule;
    }

    /**
     * Returns the direction of this mirror.
     */
    public MirrorDirection direction() {
        return direction;
    }

    /**
     * Returns the credential of this mirror.
     */
    public Credential credential() {
        return credential;
    }

    /**
     * Returns the local repository of this mirror.
     */
    public Repository localRepo() {
        return localRepo;
    }

    /**
     * Returns the local path of this mirror.
     */
    public String localPath() {
        return localPath;
    }

    /**
     * Returns the remote URI of this mirror.
     */
    public URI remoteUri() {
        return remoteUri;
    }

    /**
     * Returns the gitignore of this mirror.
     */
    @Nullable
    public String gitignore() {
        return gitignore;
    }

    /**
     * Returns the zone where this mirror is pinned.
     */
    @Nullable
    public String zone() {
        return zone;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("id", id)
                          .add("enabled", enabled)
                          .add("schedule", schedule)
                          .add("direction", direction)
                          .add("credential", credential)
                          .add("localRepo", localRepo)
                          .add("localPath", localPath)
                          .add("remoteUri", remoteUri)
                          .add("gitignore", gitignore)
                          .add("zone", zone)
                          .toString();
    }
}
