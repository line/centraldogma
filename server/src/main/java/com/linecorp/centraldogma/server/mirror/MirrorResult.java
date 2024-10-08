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

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * The result of a mirroring operation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class MirrorResult {

    private final String mirrorId;
    private final String projectName;
    private final String repoName;
    private final MirrorStatus mirrorStatus;
    @Nullable
    private final String description;
    private final Instant triggeredTime;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public MirrorResult(@JsonProperty("mirrorId") String mirrorId,
                        @JsonProperty("projectName") String projectName,
                        @JsonProperty("repoName") String repoName,
                        @JsonProperty("mirrorStatus") MirrorStatus mirrorStatus,
                        @JsonProperty("description") @Nullable String description,
                        @JsonProperty("triggeredTime") Instant triggeredTime) {
        this.mirrorId = requireNonNull(mirrorId, "mirrorId");
        this.projectName = requireNonNull(projectName, "projectName");
        this.repoName = requireNonNull(repoName, "repoName");
        this.mirrorStatus = requireNonNull(mirrorStatus, "mirrorStatus");
        this.description = description;
        this.triggeredTime = requireNonNull(triggeredTime, "triggeredTime");
    }

    /**
     * Returns the ID of the mirror.
     */
    @JsonProperty("mirrorId")
    public String mirrorId() {
        return mirrorId;
    }

    /**
     * Returns the project name which {@link #mirrorId()} belongs to.
     */
    @JsonProperty("projectName")
    public String projectName() {
        return projectName;
    }

    /**
     * Returns the repository name where the mirroring operation is performed.
     */
    @JsonProperty("repoName")
    public String repoName() {
        return repoName;
    }

    /**
     * Returns the status of the mirroring operation.
     */
    @JsonProperty("mirrorStatus")
    public MirrorStatus mirrorStatus() {
        return mirrorStatus;
    }

    /**
     * Returns the description of the mirroring operation.
     */
    @Nullable
    @JsonProperty("description")
    public String description() {
        return description;
    }

    /**
     * Returns the time when the mirroring operation was triggered.
     */
    @JsonProperty("triggeredTime")
    public Instant triggeredTime() {
        return triggeredTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MirrorResult)) {
            return false;
        }
        final MirrorResult that = (MirrorResult) o;
        return mirrorId.equals(that.mirrorId) &&
               projectName.equals(that.projectName) &&
               repoName.equals(that.repoName) &&
               mirrorStatus == that.mirrorStatus &&
               Objects.equals(description, that.description) &&
               triggeredTime.equals(triggeredTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mirrorId, projectName, repoName, mirrorStatus, description, triggeredTime);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("mirrorId", mirrorId)
                          .add("projectName", projectName)
                          .add("repoName", repoName)
                          .add("mirrorStatus", mirrorStatus)
                          .add("description", description)
                          .add("triggeredTime", triggeredTime)
                          .toString();
    }
}
