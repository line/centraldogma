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

package com.linecorp.centraldogma.server.internal.command;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;

public final class CreateRepositoryCommand extends ProjectCommand<Void> {

    private final String repositoryName;
    private final long creationTimeMillis;
    private final Author author;

    @JsonCreator
    CreateRepositoryCommand(@JsonProperty("projectName") String projectName,
                            @JsonProperty("repositoryName") String repositoryName,
                            @JsonProperty("creationTimeMillis") @Nullable Long creationTimeMillis,
                            @JsonProperty("author") @Nullable Author author) {

        super(CommandType.CREATE_REPOSITORY, projectName);
        this.repositoryName = requireNonNull(repositoryName, "repositoryName");
        this.creationTimeMillis = creationTimeMillis != null ? creationTimeMillis : System.currentTimeMillis();
        this.author = author != null ? author : Author.SYSTEM;
    }

    @JsonProperty
    public String repositoryName() {
        return repositoryName;
    }

    @JsonProperty
    public long creationTimeMillis() {
        return creationTimeMillis;
    }

    @JsonProperty
    public Author author() {
        return author;
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
               creationTimeMillis == that.creationTimeMillis &&
               author.equals(that.author);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repositoryName, creationTimeMillis, author) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("repositoryName", repositoryName)
                    .add("creationTimeMillis", creationTimeMillis);
    }
}
