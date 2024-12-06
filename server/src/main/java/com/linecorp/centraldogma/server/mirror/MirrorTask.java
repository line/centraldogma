/*
 * Copyright 2024 LINE Corporation
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

import java.time.Instant;
import java.util.Objects;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.ZoneConfig;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.project.Project;

/**
 * A task to mirror a repository.
 */
public final class MirrorTask {

    private final Mirror mirror;
    private final User triggeredBy;
    private final Instant triggeredTime;
    @Nullable
    private final String currentZone;
    private final boolean scheduled;

    /**
     * Creates a new instance.
     */
    public MirrorTask(Mirror mirror, User triggeredBy, Instant triggeredTime, @Nullable String currentZone,
                      boolean scheduled) {
        this.mirror = mirror;
        this.triggeredTime = triggeredTime;
        this.triggeredBy = triggeredBy;
        this.currentZone = currentZone;
        this.scheduled = scheduled;
    }

    /**
     * Returns the {@link Mirror} to be executed.
     */
    public Mirror mirror() {
        return mirror;
    }

    /**
     * Returns the {@link Project} where the {@link Mirror} belongs to.
     */
    public Project project() {
        return mirror.localRepo().parent();
    }

    /**
     * Returns the user who triggered the {@link Mirror}.
     */
    public User triggeredBy() {
        return triggeredBy;
    }

    /**
     * Returns the time when the {@link Mirror} was triggered.
     */
    public Instant triggeredTime() {
        return triggeredTime;
    }

    /**
     * Returns the current zone where the {@link Mirror} is running.
     * This value is {@code null} if the {@link ZoneConfig} is not available.
     */
    @Nullable
    public String currentZone() {
        return currentZone;
    }

    /**
     * Returns whether the {@link Mirror} is triggered by a scheduler.
     */
    public boolean scheduled() {
        return scheduled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MirrorTask)) {
            return false;
        }
        final MirrorTask that = (MirrorTask) o;
        return mirror.equals(that.mirror) &&
               triggeredTime.equals(that.triggeredTime) &&
               triggeredBy.equals(that.triggeredBy) &&
               scheduled == that.scheduled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mirror, triggeredTime, triggeredBy, scheduled);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("mirror", mirror)
                          .add("triggeredTime", triggeredTime)
                          .add("triggeredBy", triggeredBy)
                          .add("scheduled", scheduled)
                          .toString();
    }
}
