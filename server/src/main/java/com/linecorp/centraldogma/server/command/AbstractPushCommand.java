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

import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;

/**
 * A {@link Command} which is used for pushing changes to the repository.
 */
class AbstractPushCommand<T> extends RepositoryCommand<T> {

    private final Revision baseRevision;
    private final String summary;
    private final String detail;
    private final Markup markup;
    private final List<Change<?>> changes;

    @JsonCreator
    AbstractPushCommand(CommandType type, @Nullable Long timestamp, @Nullable Author author,
                        String projectName, String repositoryName, Revision baseRevision,
                        String summary, String detail, Markup markup, Iterable<Change<?>> changes) {
        super(type, timestamp, author, projectName, repositoryName);

        this.baseRevision = requireNonNull(baseRevision, "baseRevision");
        this.summary = requireNonNull(summary, "summary");
        this.detail = requireNonNull(detail, "detail");
        this.markup = requireNonNull(markup, "markup");

        requireNonNull(changes, "changes");
        this.changes = ImmutableList.copyOf(changes);
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
     * Returns the {@link Change}s of the commit.
     */
    @JsonProperty
    public List<Change<?>> changes() {
        return changes;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof AbstractPushCommand)) {
            return false;
        }

        final AbstractPushCommand that = (AbstractPushCommand) obj;
        return super.equals(that) &&
               baseRevision.equals(that.baseRevision) &&
               summary.equals(that.summary) &&
               detail.equals(that.detail) &&
               markup == that.markup &&
               changes.equals(that.changes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseRevision, summary, detail, markup, changes) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("baseRevision", baseRevision)
                    .add("summary", summary)
                    .add("detail", detail)
                    .add("markup", markup)
                    .add("changes", changes);
    }
}
