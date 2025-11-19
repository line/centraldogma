/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.centraldogma.server.command;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.storage.encryption.WrappedDekDetails;

/**
 * A {@link Command} that rotates the wrapped data encryption key (WDEK) for a repository.
 */
public final class RotateWdekCommand extends RepositoryCommand<Void> {

    private final WrappedDekDetails wdekDetails;

    @JsonCreator
    RotateWdekCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                      @JsonProperty("author") @Nullable Author author,
                      @JsonProperty("projectName") String projectName,
                      @JsonProperty("repositoryName") String repositoryName,
                      @JsonProperty("wdekDetails") WrappedDekDetails wdekDetails) {
        super(CommandType.ROTATE_WDEK, timestamp, author, projectName, repositoryName);
        this.wdekDetails = requireNonNull(wdekDetails, "wdekDetails");
    }

    /**
     * Returns the details of the new wrapped data encryption key (WDEK).
     */
    @JsonProperty
    public WrappedDekDetails wdekDetails() {
        return wdekDetails;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof RotateWdekCommand)) {
            return false;
        }

        final RotateWdekCommand that = (RotateWdekCommand) obj;
        return super.equals(that) &&
               wdekDetails.equals(that.wdekDetails);
    }

    @Override
    public int hashCode() {
        return Objects.hash(wdekDetails) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("wdekDetails", wdekDetails);
    }
}
