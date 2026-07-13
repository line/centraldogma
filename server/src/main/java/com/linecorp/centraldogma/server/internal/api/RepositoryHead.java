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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Revision;

/**
 * The head of a repository on the replica that served the request, identified by both its revision and
 * its commit ID. Two replicas of the same repository share a revision even when they have diverged, so
 * only the commit ID proves that they hold the same history.
 */
public final class RepositoryHead {

    private final Revision revision;
    private final String commitId;

    public RepositoryHead(Revision revision, String commitId) {
        this.revision = requireNonNull(revision, "revision");
        this.commitId = requireNonNull(commitId, "commitId");
    }

    @JsonProperty("revision")
    public Revision revision() {
        return revision;
    }

    /**
     * Returns the ID of the commit the head revision points at.
     */
    @JsonProperty("commitId")
    public String commitId() {
        return commitId;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("revision", revision)
                          .add("commitId", commitId)
                          .toString();
    }
}
