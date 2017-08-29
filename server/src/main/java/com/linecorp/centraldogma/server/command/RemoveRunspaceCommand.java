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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

public final class RemoveRunspaceCommand extends RepositoryCommand<Void> {

    private final int baseRevision;

    @JsonCreator
    RemoveRunspaceCommand(@JsonProperty("projectName") String projectName,
                          @JsonProperty("repositoryName") String repositoryName,
                          @JsonProperty("baseRevision") int baseRevision) {

        super(CommandType.REMOVE_RUNSPACE, projectName, repositoryName);
        this.baseRevision = baseRevision;
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

        if (!(obj instanceof RemoveRunspaceCommand)) {
            return false;
        }

        final RemoveRunspaceCommand that = (RemoveRunspaceCommand) obj;
        return super.equals(obj) &&
               baseRevision == that.baseRevision;
    }

    @Override
    public int hashCode() {
        return baseRevision * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper().add("baseRevision", baseRevision);
    }
}
