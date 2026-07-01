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

package com.linecorp.centraldogma.server.command;

import static com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ReplicationStatus;

/**
 * A {@link Command} which updates the replication status of a repository.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public final class UpdateRepositoryStatusCommand extends SystemAdministrativeCommand<Void> {

    private final String projectName;
    private final String repoName;
    private final ReplicationStatus repoStatus;

    /**
     * Create a new instance.
     */
    @JsonCreator
    public UpdateRepositoryStatusCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                                         @JsonProperty("author") @Nullable Author author,
                                         @JsonProperty("projectName") String projectName,
                                         @JsonProperty("repoName") String repoName,
                                         @JsonProperty("repoStatus") ReplicationStatus repoStatus) {
        super(CommandType.UPDATE_REPOSITORY_STATUS, timestamp, author);
        this.projectName = projectName;
        this.repoName = repoName;
        this.repoStatus = repoStatus;
    }

    /**
     * Returns the project name.
     */
    @JsonProperty("projectName")
    public String projectName() {
        return projectName;
    }

    /**
     * Returns the repository name.
     */
    @JsonProperty("repoName")
    public String repoName() {
        return repoName;
    }

    /**
     * Returns the replication status of the repository.
     */
    @JsonProperty("repoStatus")
    public ReplicationStatus repoStatus() {
        return repoStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof UpdateRepositoryStatusCommand)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        final UpdateRepositoryStatusCommand that = (UpdateRepositoryStatusCommand) o;
        return projectName.equals(that.projectName) &&
               repoName.equals(that.repoName) &&
               repoStatus == that.repoStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), projectName, repoName, repoStatus);
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("projectName", projectName)
                    .add("repoName", repoName)
                    .add("repoStatus", repoStatus);
    }
}
