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

package com.linecorp.centraldogma.server.storage.repository;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Revision;

/**
 * The head of a repository on a single replica: its revision, the commit the revision points at and the
 * tree that commit holds. They are read together so that they always describe the same commit. Replicas of
 * the same repository report the same revision even when their content has diverged, so the revision alone
 * proves nothing; the tree ID is what tells them apart, because it is the fingerprint of the content
 * alone. The commit ID additionally covers the parent and the timestamp, which differ between replicas of
 * a metadata repository even when their content is identical.
 */
public final class RepositoryHead {

    private final Revision revision;
    private final String commitId;
    private final String treeId;

    /**
     * Creates a new instance.
     */
    public RepositoryHead(Revision revision, String commitId, String treeId) {
        this.revision = requireNonNull(revision, "revision");
        this.commitId = requireNonNull(commitId, "commitId");
        this.treeId = requireNonNull(treeId, "treeId");
    }

    /**
     * Returns the head revision.
     */
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

    /**
     * Returns the ID of the tree that commit holds, which is the fingerprint of the content alone.
     */
    @JsonProperty("treeId")
    public String treeId() {
        return treeId;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("revision", revision)
                          .add("commitId", commitId)
                          .add("treeId", treeId)
                          .toString();
    }
}
