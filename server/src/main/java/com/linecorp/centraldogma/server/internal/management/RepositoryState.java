/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.server.internal.management;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.ReplicationStatus;

// TODO(ikhoon): Rename RepoistoryState to RepositoryStatus.
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RepositoryState {

    private final String projectName;
    private final String repoName;
    private final ReplicationStatus status;
    @Nullable
    private final Instant updatedAt;

    @JsonCreator
    public RepositoryState(@JsonProperty("projectName") String projectName,
                           @JsonProperty("repoName") String repoName,
                           @JsonProperty("status") ReplicationStatus status,
                           @JsonProperty("updatedAt") @Nullable Instant updatedAt) {
        this.projectName = requireNonNull(projectName, "projectName");
        this.repoName = requireNonNull(repoName, "repoName");
        this.status = requireNonNull(status, "status");
        this.updatedAt = updatedAt;
    }

    @JsonProperty("projectName")
    public String projectName() {
        return projectName;
    }

    @JsonProperty("repoName")
    public String repoName() {
        return repoName;
    }

    @JsonProperty("status")
    public ReplicationStatus status() {
        return status;
    }

    @JsonProperty("updatedAt")
    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RepositoryState)) {
            return false;
        }
        final RepositoryState that = (RepositoryState) o;
        return projectName.equals(that.projectName) &&
               repoName.equals(that.repoName) &&
               status == that.status &&
               Objects.equals(updatedAt, that.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectName, repoName, status, updatedAt);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("projectName", projectName)
                          .add("repoName", repoName)
                          .add("status", status)
                          .add("updatedAt", updatedAt)
                          .toString();
    }
}
