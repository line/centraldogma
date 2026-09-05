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

package com.linecorp.centraldogma.server.internal.api;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.repository.RepositoryHead;

/**
 * The head of a repository, together with the server ID of the replica that served the request. An
 * administrator compares the heads of every replica to confirm that a recovery converged, and a load
 * balancer can route two of those requests to the same replica, which would look like agreement.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
final class RepositoryHeadResponse {

    @Nullable
    private final Integer serverId;
    private final Revision revision;
    private final String commitId;
    private final String treeId;

    RepositoryHeadResponse(@Nullable Integer serverId, RepositoryHead head) {
        requireNonNull(head, "head");
        this.serverId = serverId;
        revision = head.revision();
        commitId = head.commitId();
        treeId = head.treeId();
    }

    @Nullable
    @JsonProperty("serverId")
    public Integer serverId() {
        return serverId;
    }

    @JsonProperty("revision")
    public Revision revision() {
        return revision;
    }

    @JsonProperty("commitId")
    public String commitId() {
        return commitId;
    }

    @JsonProperty("treeId")
    public String treeId() {
        return treeId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RepositoryHeadResponse)) {
            return false;
        }
        final RepositoryHeadResponse that = (RepositoryHeadResponse) o;
        return Objects.equals(serverId, that.serverId) && revision.equals(that.revision) &&
               commitId.equals(that.commitId) && treeId.equals(that.treeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverId, revision, commitId, treeId);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("serverId", serverId)
                          .add("revision", revision)
                          .add("commitId", commitId)
                          .add("treeId", treeId)
                          .toString();
    }
}
