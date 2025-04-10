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

package com.linecorp.centraldogma.server.command;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.management.ReplicationStatus;

/**
 * A {@link Command} which is used to update the status of a repository.
 */
@JsonInclude(Include.NON_NULL)
public final class UpdateRepositoryStatusCommand extends RepositoryCommand<Void> {

    private final ReplicationStatus replicationStatus;

    /**
     * Creates a new instance with the specified properties.
     */
    @JsonCreator
    public UpdateRepositoryStatusCommand(@JsonProperty("projectName") String projectName,
                                         @JsonProperty("repositoryName") String repositoryName,
                                         @JsonProperty("author") @Nullable Author author,
                                         @JsonProperty("replicationStatus") ReplicationStatus replicationStatus) {
        super(CommandType.UPDATE_REPOSITORY_STATUS, null, author, projectName, repositoryName);
        this.replicationStatus = requireNonNull(replicationStatus, "replicationStatus");
    }

    /**
     * Returns the status of the repository.
     */
    @JsonProperty("replicationStatus")
    public ReplicationStatus replicationStatus() {
        return replicationStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UpdateRepositoryStatusCommand)) {
            return false;
        }
        final UpdateRepositoryStatusCommand that = (UpdateRepositoryStatusCommand) o;

        return super.equals(that) && replicationStatus == that.replicationStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), replicationStatus);
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("replicationStatus", replicationStatus);
    }
}
