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

package com.linecorp.centraldogma.internal.api.v1;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

public final class RevertRequest {

    private final int targetRevision;
    private final CommitMessageDto commitMessage;

    public RevertRequest(@JsonProperty("targetRevision") int targetRevision,
                         @JsonProperty("commitMessage") CommitMessageDto commitMessage) {
        this.targetRevision = targetRevision;
        this.commitMessage = requireNonNull(commitMessage, "commitMessage");
    }

    @JsonProperty("targetRevision")
    public int targetRevision() {
        return targetRevision;
    }

    @JsonProperty("commitMessage")
    public CommitMessageDto commitMessage() {
        return commitMessage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RevertRequest)) {
            return false;
        }
        final RevertRequest that = (RevertRequest) o;
        return targetRevision == that.targetRevision && commitMessage.equals(that.commitMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetRevision, commitMessage);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("targetRevision", targetRevision)
                          .add("commitMessage", commitMessage)
                          .toString();
    }
}
