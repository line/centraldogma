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

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RecoverRepositoryResponse {

    private final RecoveryStatus status;
    @Nullable
    private final Integer headRevision;

    public RecoverRepositoryResponse(RecoveryStatus status, @Nullable Integer headRevision) {
        this.status = requireNonNull(status, "status");
        this.headRevision = headRevision;
    }

    @JsonProperty("status")
    public RecoveryStatus status() {
        return status;
    }

    /**
     * Returns the source replica's head revision, which every other replica converges to once it replays
     * the recovery, or {@code null} if the recovery was only requested.
     */
    @Nullable
    @JsonProperty("headRevision")
    public Integer headRevision() {
        return headRevision;
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
        return status == that.status && Objects.equals(headRevision, that.headRevision);
    }

    @Override
    public int hashCode() {
        return status.hashCode() * 31 + Objects.hashCode(headRevision);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("status", status)
                          .add("headRevision", headRevision)
                          .toString();
    }
}
