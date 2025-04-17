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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;

/**
 * A {@link Command} which is used for resetting a meta repository.
 *
 * @deprecated This class will be removed after migrating the content in meta repository to dogma repository.
 */
@Deprecated
public final class ResetMetaRepositoryCommand extends ProjectCommand<Void> {

    @JsonCreator
    ResetMetaRepositoryCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                               @JsonProperty("author") Author author,
                               @JsonProperty("projectName") String projectName) {
        super(CommandType.RESET_META_REPOSITORY, timestamp, author, projectName);
    }

    // hashCode must be existed when equals is overridden.
    @Override
    public int hashCode() {
        return super.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof ResetMetaRepositoryCommand)) {
            return false;
        }

        return super.equals(obj);
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper();
    }
}
