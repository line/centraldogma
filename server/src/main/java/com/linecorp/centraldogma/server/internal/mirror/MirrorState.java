/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.mirror;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

public final class MirrorState {

    private final String sourceRevision;
    @Nullable
    private final String remoteRevision;
    @Nullable
    private final String remotePath;
    @Nullable
    private final String localRevision;
    @Nullable
    private final String localPath;

    // TODO(ikhoon): Remove sourceRevision and use either remoteRevision or localRevision.
    @JsonCreator
    public MirrorState(@JsonProperty(value = "sourceRevision", required = true) String sourceRevision,
                       @JsonProperty("remoteRevision") @Nullable String remoteRevision,
                       @JsonProperty("remotePath") @Nullable String remotePath,
                       @JsonProperty("localRevision") @Nullable String localRevision,
                       @JsonProperty("localPath") @Nullable String localPath) {
        this.sourceRevision = requireNonNull(sourceRevision, "sourceRevision");
        this.remoteRevision = remoteRevision;
        this.remotePath = remotePath;
        this.localRevision = localRevision;
        this.localPath = localPath;
    }

    /**
     * Returns the source revision. It could be a remote commit hash or a revision version of a local repo
     * depending on the mirror type.
     *
     * @deprecated Use {@link #remoteRevision()} or {@link #localRevision()} instead.
     */
    @Deprecated
    @JsonProperty("sourceRevision")
    public String sourceRevision() {
        return sourceRevision;
    }

    @JsonProperty("remoteRevision")
    @Nullable
    public String remoteRevision() {
        return remoteRevision;
    }

    @JsonProperty("remotePath")
    @Nullable
    public String remotePath() {
        return remotePath;
    }

    @JsonProperty("localRevision")
    @Nullable
    public String localRevision() {
        return localRevision;
    }

    @JsonProperty("localPath")
    @Nullable
    public String localPath() {
        return localPath;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof MirrorState)) {
            return false;
        }
        final MirrorState that = (MirrorState) o;
        return sourceRevision.equals(that.sourceRevision) &&
               Objects.equals(remoteRevision, that.remoteRevision) &&
               Objects.equals(remotePath, that.remotePath) &&
               Objects.equals(localRevision, that.localRevision) &&
               Objects.equals(localPath, that.localPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceRevision, remoteRevision, remotePath, localRevision, localPath);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("sourceRevision", sourceRevision)
                          .add("remoteRevision", remoteRevision)
                          .add("remotePath", remotePath)
                          .add("localRevision", localRevision)
                          .add("localPath", localPath)
                          .toString();
    }
}
