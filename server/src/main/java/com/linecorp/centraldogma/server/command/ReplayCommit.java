/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;

/**
 * A single commit replayed onto a diverged replica during a {@link RecoverRepositoryCommand}. It carries the
 * original commit metadata and a self-contained set of {@link Change}s so that every replica reconstructs an
 * identical commit (and thus an identical commit id) when it is applied on top of the previous revision.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ReplayCommit {

    private final Revision revision;
    private final long timestampMillis;
    private final Author author;
    private final String summary;
    private final String detail;
    private final Markup markup;
    private final List<Change<?>> changes;
    private final String expectedCommitId;

    /**
     * Creates a new instance.
     */
    @JsonCreator
    public ReplayCommit(@JsonProperty("revision") Revision revision,
                        @JsonProperty("timestampMillis") long timestampMillis,
                        @JsonProperty("author") Author author,
                        @JsonProperty("summary") String summary,
                        @JsonProperty("detail") String detail,
                        @JsonProperty("markup") Markup markup,
                        @JsonProperty("changes") Iterable<Change<?>> changes,
                        @JsonProperty("expectedCommitId") String expectedCommitId) {
        this.revision = requireNonNull(revision, "revision");
        this.timestampMillis = timestampMillis;
        this.author = requireNonNull(author, "author");
        this.summary = requireNonNull(summary, "summary");
        this.detail = requireNonNull(detail, "detail");
        this.markup = requireNonNull(markup, "markup");
        this.changes = ImmutableList.copyOf(requireNonNull(changes, "changes"));
        this.expectedCommitId = requireNonNull(expectedCommitId, "expectedCommitId");
    }

    /**
     * Returns the {@link Revision} produced by this commit.
     */
    @JsonProperty
    public Revision revision() {
        return revision;
    }

    /**
     * Returns the commit time in milliseconds.
     */
    @JsonProperty
    public long timestampMillis() {
        return timestampMillis;
    }

    /**
     * Returns the {@link Author} of the commit.
     */
    @JsonProperty
    public Author author() {
        return author;
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
     * Returns the self-contained {@link Change}s of the commit.
     */
    @JsonProperty
    public List<Change<?>> changes() {
        return changes;
    }

    /**
     * Returns the commit id the replayed commit must produce. A replica that produces a different one
     * aborts the recovery instead of writing a history that diverges from the source, so this is what
     * makes a recovery verifiable rather than merely hopeful.
     */
    @JsonProperty
    public String expectedCommitId() {
        return expectedCommitId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReplayCommit)) {
            return false;
        }
        final ReplayCommit that = (ReplayCommit) o;
        return timestampMillis == that.timestampMillis &&
               revision.equals(that.revision) &&
               author.equals(that.author) &&
               summary.equals(that.summary) &&
               detail.equals(that.detail) &&
               markup == that.markup &&
               changes.equals(that.changes) &&
               Objects.equals(expectedCommitId, that.expectedCommitId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(revision, timestampMillis, author, summary, detail, markup, changes,
                            expectedCommitId);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("revision", revision)
                          .add("timestampMillis", timestampMillis)
                          .add("author", author)
                          .add("summary", summary)
                          .add("markup", markup)
                          .add("changes", changes.size())
                          .add("expectedCommitId", expectedCommitId)
                          .toString();
    }
}
