/*
 * Copyright 2021 LINE Corporation
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

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;

/**
 * A {@link Command} which is used replicate a {@link NormalizingPushCommand} to other replicas.
 * Unlike {@link NormalizingPushCommand}, the changes of this {@link Command}
 * are not normalized and applied as they are.
 */
public final class PushAsIsCommand extends AbstractPushCommand<Revision> {

    /**
     * Creates a new instance.
     */
    @JsonCreator
    PushAsIsCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                    @JsonProperty("author") @Nullable Author author,
                    @JsonProperty("projectName") String projectName,
                    @JsonProperty("repositoryName") String repositoryName,
                    @JsonProperty("baseRevision") Revision baseRevision,
                    @JsonProperty("summary") String summary,
                    @JsonProperty("detail") String detail,
                    @JsonProperty("markup") Markup markup,
                    @JsonProperty("changes") Iterable<Change<?>> changes) {
        super(CommandType.PUSH, timestamp, author, projectName, repositoryName,
              baseRevision, summary, detail, markup, changes);
    }
}
