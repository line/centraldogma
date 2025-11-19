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

import static com.google.common.base.Preconditions.checkArgument;
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
 * A {@link Command} which is used for creating a new project.
 */
@JsonInclude(Include.NON_NULL)
public final class CreateProjectCommand extends RootCommand<Void> {

    private final String projectName;
    @Nullable
    private final WrappedDekDetails wdekDetails;

    @JsonCreator
    CreateProjectCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                         @JsonProperty("author") @Nullable Author author,
                         @JsonProperty("projectName") String projectName,
                         @JsonProperty("wdekDetails") @Nullable WrappedDekDetails wdekDetails) {
        super(CommandType.CREATE_PROJECT, timestamp, author);
        this.projectName = requireNonNull(projectName, "projectName");
        this.wdekDetails = wdekDetails;
        if (wdekDetails != null) {
            checkArgument(wdekDetails.projectName().equals(projectName),
                          "projectName: %s, (expected: %s", projectName, wdekDetails.projectName());
        }
    }

    /**
     * Returns the project name.
     */
    @JsonProperty
    public String projectName() {
        return projectName;
    }

    /**
     * Returns the wrapped data encryption key (WDEK) of the project.
     */
    @Nullable
    @JsonProperty
    public WrappedDekDetails wdekDetails() {
        return wdekDetails;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof CreateProjectCommand)) {
            return false;
        }

        final CreateProjectCommand that = (CreateProjectCommand) obj;
        return super.equals(obj) &&
               projectName.equals(that.projectName) &&
               Objects.equals(wdekDetails, that.wdekDetails);
    }

    @Override
    public int hashCode() {
        return (projectName.hashCode() * 31 + Objects.hashCode(wdekDetails)) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper().omitNullValues()
                    .add("projectName", projectName)
                    .add("wdekDetails", wdekDetails);
    }
}
