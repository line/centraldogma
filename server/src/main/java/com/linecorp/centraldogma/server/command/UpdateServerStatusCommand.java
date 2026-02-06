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

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.management.ServerStatus;

/**
 * A {@link Command} which is used to update the status of all servers in the cluster.
 */
@JsonInclude(Include.NON_NULL)
public final class UpdateServerStatusCommand extends SystemAdministrativeCommand<Void> {

    private final ServerStatus serverStatus;

    /**
     * Creates a new instance with the specified properties.
     */
    @JsonCreator
    public UpdateServerStatusCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                                     @JsonProperty("author") @Nullable Author author,
                                     @JsonProperty("serverStatus") ServerStatus serverStatus) {
        super(CommandType.UPDATE_SERVER_STATUS, timestamp, author);
        this.serverStatus = serverStatus;
    }

    /**
     * Returns the status of the server.
     */
    @JsonProperty("serverStatus")
    public ServerStatus serverStatus() {
        return serverStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UpdateServerStatusCommand)) {
            return false;
        }
        final UpdateServerStatusCommand that = (UpdateServerStatusCommand) o;

        return super.equals(that) && serverStatus == that.serverStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), serverStatus);
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("serverStatus", serverStatus);
    }
}
