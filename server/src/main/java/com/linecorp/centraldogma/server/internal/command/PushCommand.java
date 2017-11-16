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

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;

public final class PushCommand extends RepositoryCommand<Revision> {

    private final Revision baseRevision;
    private final String summary;
    private final String detail;
    private final Markup markup;
    private final List<Change<?>> changes;

    @JsonCreator
    PushCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                @JsonProperty("author") @Nullable Author author,
                @JsonProperty("projectName") String projectName,
                @JsonProperty("repositoryName") String repositoryName,
                @JsonProperty("baseRevision") Revision baseRevision,
                @JsonProperty("summary") String summary,
                @JsonProperty("detail") String detail,
                @JsonProperty("markup") Markup markup,
                @JsonProperty("changes") Iterable<Change<?>> changes) {

        super(CommandType.PUSH, timestamp, author, projectName, repositoryName);

        this.baseRevision = requireNonNull(baseRevision, "baseRevision");
        this.summary = requireNonNull(summary, "summary");
        this.detail = requireNonNull(detail, "detail");
        this.markup = requireNonNull(markup, "markup");

        requireNonNull(changes, "changes");
        this.changes = Collections.unmodifiableList(
                StreamSupport.stream(changes.spliterator(), false).collect(Collectors.toList()));
    }

    @JsonProperty
    public Revision baseRevision() {
        return baseRevision;
    }

    @JsonProperty
    public String summary() {
        return summary;
    }

    @JsonProperty
    public String detail() {
        return detail;
    }

    @JsonProperty
    public Markup markup() {
        return markup;
    }

    @JsonProperty
    public List<Change<?>> changes() {
        return changes;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof PushCommand)) {
            return false;
        }

        final PushCommand that = (PushCommand) obj;
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
                    .add("markup", markup);
    }
}
