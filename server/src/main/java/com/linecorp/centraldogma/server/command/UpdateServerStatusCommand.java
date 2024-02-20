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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;

/**
 * A {@link Command} which is used to update the status of all servers in the cluster.
 */
@JsonInclude(Include.NON_NULL)
public final class UpdateServerStatusCommand extends AdministrativeCommand<Void> {

    @Nullable
    private final Boolean writable;
    @Nullable
    private final Boolean replicating;

    /**
     * Creates a new instance with the specified properties.
     */
    @JsonCreator
    public UpdateServerStatusCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                                     @JsonProperty("author") @Nullable Author author,
                                     @JsonProperty("writable") @Nullable Boolean writable,
                                     @JsonProperty("replicating") @Nullable Boolean replicating) {
        super(CommandType.UPDATE_SERVER_STATUS, timestamp, author);
        this.writable = writable;
        this.replicating = replicating;
    }

    /**
     * Returns whether the cluster is writable.
     */
    @Nullable
    @JsonProperty("writable")
    public Boolean writable() {
        return writable;
    }

    /**
     * Returns whether the cluster is replicating.
     */
    @Nullable
    @JsonProperty("replicating")
    public Boolean replicating() {
        return replicating;
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

        return super.equals(that) && writable == that.writable && replicating == that.replicating;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), writable, replicating);
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("writable", writable)
                    .add("replicating", replicating);
    }
}
