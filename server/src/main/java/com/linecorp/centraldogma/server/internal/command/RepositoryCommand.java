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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;

public abstract class RepositoryCommand<T> extends AbstractCommand<T> {

    private final String projectName;
    private final String repositoryName;

    RepositoryCommand(CommandType commandType, @Nullable Long timestamp, @Nullable Author author,
                      String projectName, String repositoryName) {
        super(commandType, timestamp, author);
        this.projectName = requireNonNull(projectName, "projectName");
        this.repositoryName = requireNonNull(repositoryName, "repositoryName");
    }

    @JsonProperty
    public final String projectName() {
        return projectName;
    }

    @JsonProperty
    public final String repositoryName() {
        return repositoryName;
    }

    @Override
    public final String executionPath() {
        return String.format("/%s/%s", projectName, repositoryName);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof RepositoryCommand)) {
            return false;
        }

        final RepositoryCommand<?> that = (RepositoryCommand<?>) obj;
        return super.equals(that) &&
               projectName.equals(that.projectName) &&
               repositoryName.equals(that.repositoryName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectName, repositoryName) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("projectName", projectName)
                    .add("repositoryName", repositoryName);
    }
}
