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

import com.linecorp.centraldogma.server.mirror.MirrorDirection;

public final class MirrorState {

    private final String sourceRevision;
    @Nullable
    private final String remoteRevision;
    @Nullable
    private final String localRevision;
    @Nullable
    private final MirrorDirection direction;
    @Nullable
    private final String configHash;

    // TODO(ikhoon): Remove sourceRevision and use either remoteRevision or localRevision.
    @JsonCreator
    public MirrorState(@JsonProperty(value = "sourceRevision", required = true) String sourceRevision,
                       @JsonProperty("remoteRevision") @Nullable String remoteRevision,
                       @JsonProperty("localRevision") @Nullable String localRevision,
                       @JsonProperty("direction") @Nullable MirrorDirection direction,
                       @JsonProperty("configHash") @Nullable String configHash) {
        this.sourceRevision = requireNonNull(sourceRevision, "sourceRevision");
        this.remoteRevision = remoteRevision;
        this.localRevision = localRevision;
        this.direction = direction;
        this.configHash = configHash;
    }

    /**
     * Returns the source revision. It could be a remote commit hash or a revision version of a local repo
     * depending on the mirror type.
     *
     * @deprecated Use {@link #previousTargetRevision()} instead.
     */
    @Deprecated
    @JsonProperty("sourceRevision")
    public String sourceRevision() {
        return sourceRevision;
    }

    /**
     * Returns the revision of the source repository that was used as the target in the previous mirroring
     * operation.
     */
    @Nullable
    public String previousTargetRevision() {
        if (direction == MirrorDirection.REMOTE_TO_LOCAL) {
            return remoteRevision;
        } else if (direction == MirrorDirection.LOCAL_TO_REMOTE) {
            return localRevision;
        } else {
            return null;
        }
    }

    @JsonProperty("remoteRevision")
    @Nullable
    public String remoteRevision() {
        return remoteRevision;
    }

    @JsonProperty("localRevision")
    @Nullable
    public String localRevision() {
        return localRevision;
    }

    @JsonProperty("direction")
    @Nullable
    public MirrorDirection direction() {
        return direction;
    }

    @JsonProperty("configHash")
    @Nullable
    public String configHash() {
        return configHash;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof MirrorState)) {
            return false;
        }
        final MirrorState that = (MirrorState) o;
        return sourceRevision.equals(that.sourceRevision) &&
               Objects.equals(remoteRevision, that.remoteRevision) &&
               Objects.equals(localRevision, that.localRevision) &&
               direction == that.direction &&
               Objects.equals(configHash, that.configHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceRevision, remoteRevision, localRevision, direction, configHash);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("sourceRevision", sourceRevision)
                          .add("remoteRevision", remoteRevision)
                          .add("localRevision", localRevision)
                          .add("direction", direction)
                          .add("configHash", configHash)
                          .toString();
    }
}
