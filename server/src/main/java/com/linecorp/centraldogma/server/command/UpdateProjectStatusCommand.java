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
 * A {@link Command} which is used to update the status of a project.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public final class UpdateProjectStatusCommand extends SystemAdministrativeCommand<Void> {

    private final String projectName;
    private final ReplicationStatus replicationStatus;

    /**
     * Creates a new instance with the specified properties.
     */
    @JsonCreator
    public UpdateProjectStatusCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                                      @JsonProperty("author") @Nullable Author author,
                                      @JsonProperty("projectName") String projectName,
                                      @JsonProperty("projectStatus") ReplicationStatus replicationStatus) {
        super(CommandType.UPDATE_PROJECT_STATUS, timestamp, author);
        this.projectName = projectName;
        this.replicationStatus = replicationStatus;
    }

    /**
     * Returns the project name.
     */
    @JsonProperty("projectName")
    public String projectName() {
        return projectName;
    }

    /**
     * Returns the status of the project.
     */
    @JsonProperty("projectStatus")
    public ReplicationStatus projectStatus() {
        return replicationStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof UpdateProjectStatusCommand)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final UpdateProjectStatusCommand that = (UpdateProjectStatusCommand) o;
        return projectName.equals(that.projectName) && replicationStatus == that.replicationStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), projectName, replicationStatus);
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("projectName", projectName)
                    .add("projectStatus", replicationStatus);
    }
}
