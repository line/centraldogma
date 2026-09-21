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

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.command.ApplyRepositoryRecoveryCommand;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class RecoverRepositoryRequest {

    private final int fromRevision;
    private final int toRevision;
    private final int sourceServerId;
    private final int maxRevision;

    @JsonCreator
    public RecoverRepositoryRequest(@JsonProperty("fromRevision") int fromRevision,
                                    @JsonProperty("toRevision") int toRevision,
                                    @JsonProperty(value = "maxRevision", required = true) int maxRevision,
                                    @JsonProperty("sourceServerId") int sourceServerId) {
        checkArgument(fromRevision >= 2, "fromRevision: %s (expected: >= 2)", fromRevision);
        checkArgument(toRevision >= fromRevision,
                      "toRevision: %s (expected: >= fromRevision %s)", toRevision, fromRevision);
        checkArgument(toRevision - fromRevision + 1 <= ApplyRepositoryRecoveryCommand.MAX_RECOVERY_COMMITS,
                      "%s..%s spans too many revisions (maximum: %s). Narrow the range.",
                      fromRevision, toRevision, ApplyRepositoryRecoveryCommand.MAX_RECOVERY_COMMITS);
        checkArgument(sourceServerId > 0, "sourceServerId: %s (expected: > 0)", sourceServerId);
        checkArgument(maxRevision >= toRevision,
                      "maxRevision: %s (expected: >= toRevision %s)", maxRevision, toRevision);
        checkArgument(maxRevision < Integer.MAX_VALUE,
                      "maxRevision: %s (expected: < %s)", maxRevision, Integer.MAX_VALUE);
        checkArgument((long) maxRevision - fromRevision + 2 <=
                      ApplyRepositoryRecoveryCommand.MAX_RECOVERY_COMMITS,
                      "recovery spans too many revisions (maximum: %s)",
                      ApplyRepositoryRecoveryCommand.MAX_RECOVERY_COMMITS);
        this.fromRevision = fromRevision;
        this.toRevision = toRevision;
        this.sourceServerId = sourceServerId;
        this.maxRevision = maxRevision;
    }

    @JsonProperty("fromRevision")
    public int fromRevision() {
        return fromRevision;
    }

    @JsonProperty("toRevision")
    public int toRevision() {
        return toRevision;
    }

    @JsonProperty("sourceServerId")
    public int sourceServerId() {
        return sourceServerId;
    }

    @JsonProperty("maxRevision")
    public int maxRevision() {
        return maxRevision;
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
        return fromRevision == that.fromRevision && toRevision == that.toRevision &&
               sourceServerId == that.sourceServerId && maxRevision == that.maxRevision;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromRevision, toRevision, sourceServerId, maxRevision);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("fromRevision", fromRevision)
                          .add("toRevision", toRevision)
                          .add("sourceServerId", sourceServerId)
                          .add("maxRevision", maxRevision)
                          .toString();
    }
}
