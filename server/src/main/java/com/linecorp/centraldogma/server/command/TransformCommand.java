/*
 * Copyright 2024 LINE Corporation
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

import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;

/**
 * A {@link Command} which is used for pushing a change to the repository. The change is created by transforming
 * the content of the base revision with the specified {@link ContentTransformer}.
 * You can find the normalized changes from the {@link CommitResult#changes()} that is the result of
 * {@link CommandExecutor#execute(Command)}. Note that this command is not serialized and deserialized.
 */
public final class TransformCommand extends RepositoryCommand<CommitResult> {

    /**
     * Creates a new instance.
     */
    public static TransformCommand of(@Nullable Long timestamp,
                                      @Nullable Author author, String projectName,
                                      String repositoryName, Revision baseRevision,
                                      String summary, String detail, Markup markup,
                                      ContentTransformer<?> transformer) {
        return new TransformCommand(timestamp, author, projectName, repositoryName,
                                    baseRevision, summary, detail, markup, transformer);
    }

    private final Revision baseRevision;
    private final String summary;
    private final String detail;
    private final Markup markup;
    private final ContentTransformer<?> transformer;

    private TransformCommand(@Nullable Long timestamp, @Nullable Author author,
                             String projectName, String repositoryName, Revision baseRevision,
                             String summary, String detail, Markup markup,
                             ContentTransformer<?> transformer) {
        super(CommandType.TRANSFORM, timestamp, author, projectName, repositoryName);
        this.baseRevision = baseRevision;
        this.summary = summary;
        this.detail = detail;
        this.markup = markup;
        this.transformer = transformer;
    }

    /**
     * Returns the base {@link Revision}.
     */
    @JsonProperty
    public Revision baseRevision() {
        return baseRevision;
    }

    /**
     * Returns the human-readable summary of the commit.
     */
    @JsonProperty
    public String summary() {
        return summary;
    }

    /**
     * Returns the human-readable detail of the commit.
     */
    @JsonProperty
    public String detail() {
        return detail;
    }

    /**
     * Returns the {@link Markup} of the {@link #detail()}.
     */
    @JsonProperty
    public Markup markup() {
        return markup;
    }

    /**
     * Returns the {@link ContentTransformer} which is used for transforming the content.
     */
    public ContentTransformer<?> transformer() {
        return transformer;
    }

    /**
     * Returns a new {@link PushAsIsCommand} which is converted from this {@link TransformCommand}
     * for replicating to other replicas. Unlike the {@link TransformCommand},
     * the changes of this {@link Command} are not normalized and applied as they are.
     */
    public PushAsIsCommand asIs(CommitResult commitResult) {
        requireNonNull(commitResult, "commitResult");
        return new PushAsIsCommand(timestamp(), author(), projectName(), repositoryName(),
                                   commitResult.revision().backward(1), summary(), detail(),
                                   markup(), commitResult.changes());
    }
}
