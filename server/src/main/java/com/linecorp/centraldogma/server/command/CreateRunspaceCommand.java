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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;

public final class CreateRunspaceCommand extends RepositoryCommand<Void> {

    private final Author author;
    private final int baseRevision;

    @JsonCreator
    CreateRunspaceCommand(@JsonProperty("projectName") String projectName,
                          @JsonProperty("repositoryName") String repositoryName,
                          @JsonProperty("author") Author author,
                          @JsonProperty("baseRevision") int baseRevision) {

        super(CommandType.CREATE_RUNSPACE, projectName, repositoryName);
        this.author = requireNonNull(author, "author");
        this.baseRevision = baseRevision;
    }

    @JsonProperty
    public Author author() {
        return author;
    }

    @JsonProperty
    public int baseRevision() {
        return baseRevision;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof CreateRunspaceCommand)) {
            return false;
        }

        final CreateRunspaceCommand that = (CreateRunspaceCommand) obj;
        return super.equals(that) &&
               baseRevision == that.baseRevision &&
               author.equals(that.author);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseRevision, author) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("baseRevision", baseRevision)
                    .add("author", author);
    }
}
