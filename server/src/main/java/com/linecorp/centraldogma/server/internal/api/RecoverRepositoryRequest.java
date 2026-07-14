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

import static com.google.common.base.Preconditions.checkArgument;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

public final class RecoverRepositoryRequest {

    private final int fromRevision;
    private final int sourceServerId;

    @JsonCreator
    public RecoverRepositoryRequest(@JsonProperty("fromRevision") int fromRevision,
                                    @JsonProperty("sourceServerId") int sourceServerId) {
        checkArgument(fromRevision >= 2, "fromRevision: %s (expected: >= 2)", fromRevision);
        checkArgument(sourceServerId > 0, "sourceServerId: %s (expected: > 0)", sourceServerId);
        this.fromRevision = fromRevision;
        this.sourceServerId = sourceServerId;
    }

    @JsonProperty("fromRevision")
    public int fromRevision() {
        return fromRevision;
    }

    @JsonProperty("sourceServerId")
    public int sourceServerId() {
        return sourceServerId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RecoverRepositoryRequest)) {
            return false;
        }
        final RecoverRepositoryRequest that = (RecoverRepositoryRequest) o;
        return fromRevision == that.fromRevision && sourceServerId == that.sourceServerId;
    }

    @Override
    public int hashCode() {
        return fromRevision * 31 + sourceServerId;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("fromRevision", fromRevision)
                          .add("sourceServerId", sourceServerId)
                          .toString();
    }
}
