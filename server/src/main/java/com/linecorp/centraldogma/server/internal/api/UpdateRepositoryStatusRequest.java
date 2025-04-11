/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.api;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.management.ReplicationStatus;

public final class UpdateRepositoryStatusRequest {

    private final ReplicationStatus replicationStatus;

    @JsonCreator
    public UpdateRepositoryStatusRequest(
            @JsonProperty("replicationStatus") ReplicationStatus replicationStatus) {
        this.replicationStatus = requireNonNull(replicationStatus, "replicationStatus");
    }

    @JsonProperty("replicationStatus")
    public ReplicationStatus replicationStatus() {
        return replicationStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UpdateRepositoryStatusRequest)) {
            return false;
        }
        final UpdateRepositoryStatusRequest that = (UpdateRepositoryStatusRequest) o;
        return replicationStatus == that.replicationStatus;
    }

    @Override
    public int hashCode() {
        return replicationStatus.hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("replicationStatus", replicationStatus)
                          .toString();
    }
}
