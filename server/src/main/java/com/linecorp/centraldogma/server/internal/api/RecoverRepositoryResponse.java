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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.Revision;

final class RecoverRepositoryResponse {

    private final Revision recoveryRevision;

    RecoverRepositoryResponse(Revision recoveryRevision) {
        this.recoveryRevision = requireNonNull(recoveryRevision, "recoveryRevision");
    }

    /**
     * Returns the revision every replica converges to after replaying the recovery and compatibility
     * padding.
     */
    @JsonProperty("recoveryRevision")
    Revision recoveryRevision() {
        return recoveryRevision;
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
        return Objects.equals(recoveryRevision, that.recoveryRevision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recoveryRevision);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("recoveryRevision", recoveryRevision)
                          .toString();
    }
}
