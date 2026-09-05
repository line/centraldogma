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

@JsonInclude(JsonInclude.Include.NON_NULL)
final class RecoverRepositoryResponse {

    private final RecoveryStatus status;
    @Nullable
    private final Revision toRevision;

    RecoverRepositoryResponse(RecoveryStatus status, @Nullable Revision toRevision) {
        this.status = requireNonNull(status, "status");
        this.toRevision = toRevision;
    }

    @JsonProperty("status")
    RecoveryStatus status() {
        return status;
    }

    /**
     * Returns the revision every replica converges to once it replays the recovery, or {@code null} if the
     * recovery was only requested. It is the {@code toRevision} of the request, which need not be the
     * source replica's head.
     */
    @Nullable
    @JsonProperty("toRevision")
    Revision toRevision() {
        return toRevision;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RecoverRepositoryResponse)) {
            return false;
        }
        final RecoverRepositoryResponse that = (RecoverRepositoryResponse) o;
        return status == that.status && Objects.equals(toRevision, that.toRevision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, toRevision);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("status", status)
                          .add("toRevision", toRevision)
                          .toString();
    }
}
