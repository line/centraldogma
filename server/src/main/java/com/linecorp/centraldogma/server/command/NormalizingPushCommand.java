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

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.repository.Repository;

/**
 * A {@link Command} which is used for pushing changes to the repository. The changes are normalized via
 * {@link Repository#previewDiff(Revision, Iterable)} before they are applied.
 * You can find the normalized changes from the {@link CommitResult#changes()} that is the result of
 * {@link CommandExecutor#execute(Command)}.
 */
public final class NormalizingPushCommand extends ChangesPushCommand<CommitResult> {

    @JsonCreator
    NormalizingPushCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                           @JsonProperty("author") @Nullable Author author,
                           @JsonProperty("projectName") String projectName,
                           @JsonProperty("repositoryName") String repositoryName,
                           @JsonProperty("baseRevision") Revision baseRevision,
                           @JsonProperty("summary") String summary,
                           @JsonProperty("detail") String detail,
                           @JsonProperty("markup") Markup markup,
                           @JsonProperty("changes") Iterable<Change<?>> changes) {
        super(CommandType.NORMALIZING_PUSH, timestamp, author, projectName, repositoryName,
              baseRevision, summary, detail, markup, changes);
    }

    /**
     * Returns a new {@link PushAsIsCommand} which is converted from this {@link NormalizingPushCommand}
     * for replicating to other replicas. Unlike the {@link NormalizingPushCommand},
     * the changes of this {@link Command} are not normalized and applied as they are.
     */
    public PushAsIsCommand asIs(CommitResult commitResult) {
        requireNonNull(commitResult, "commitResult");
        return new PushAsIsCommand(timestamp(), author(), projectName(), repositoryName(),
                                   commitResult.revision().backward(1), summary(), detail(),
                                   markup(), commitResult.changes());
    }
}
