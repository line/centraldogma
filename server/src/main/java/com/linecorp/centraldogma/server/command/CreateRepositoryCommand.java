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

package com.linecorp.centraldogma.server.command;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.storage.encryption.WrappedDekDetails;

/**
 * A {@link Command} which is used for creating a new repository.
 */
@JsonInclude(Include.NON_NULL)
public final class CreateRepositoryCommand extends ProjectCommand<Void> {

    private final String repositoryName;
    @Nullable
    private final WrappedDekDetails wdekDetails;

    @JsonCreator
    CreateRepositoryCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                            @JsonProperty("author") @Nullable Author author,
                            @JsonProperty("projectName") String projectName,
                            @JsonProperty("repositoryName") String repositoryName,
                            @JsonProperty("wdek") @Nullable WrappedDekDetails wdekDetails) {
        super(CommandType.CREATE_REPOSITORY, timestamp, author, projectName);
        this.repositoryName = requireNonNull(repositoryName, "repositoryName");
        this.wdekDetails = wdekDetails;
    }

    /**
     * Returns the repository name.
     */
    @JsonProperty
    public String repositoryName() {
        return repositoryName;
    }

    /**
     * Returns the WDEK of the repository.
     */
    @JsonProperty
    @Nullable
    public WrappedDekDetails wdekDetails() {
        return wdekDetails;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof CreateRepositoryCommand)) {
            return false;
        }

        final CreateRepositoryCommand that = (CreateRepositoryCommand) obj;
        return super.equals(obj) &&
               repositoryName.equals(that.repositoryName) &&
               Objects.equals(wdekDetails, that.wdekDetails);
    }

    @Override
    public int hashCode() {
        return (repositoryName.hashCode() * 31 + Objects.hashCode(wdekDetails)) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        final ToStringHelper toStringHelper = super.toStringHelper()
                                                   .add("repositoryName", repositoryName);
        if (wdekDetails != null) {
            toStringHelper.add("wdekDetails", wdekDetails);
        }
        return toStringHelper;
    }
}
