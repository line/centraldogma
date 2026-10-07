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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;

import org.eclipse.jgit.lib.ObjectId;
import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Ascii;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;

/**
 * A {@link Command} which is used for pushing changes to the repository.
 */
public abstract class AbstractPushCommand<T> extends RepositoryCommand<T> {

    private final Revision baseRevision;
    private final String summary;
    private final String detail;
    private final Markup markup;
    private final List<Change<?>> changes;
    @Nullable
    private final String upstreamCommitId;
    private final boolean publishUpstreamCommitTag;

    AbstractPushCommand(CommandType type, @Nullable Long timestamp, @Nullable Author author,
                        String projectName, String repositoryName, Revision baseRevision,
                        String summary, String detail, Markup markup, Iterable<Change<?>> changes,
                        @Nullable String upstreamCommitId, @Nullable Boolean publishUpstreamCommitTag) {
        super(type, timestamp, author, projectName, repositoryName);

        if (upstreamCommitId != null) {
            checkArgument(ObjectId.isId(upstreamCommitId) &&
                          Ascii.toLowerCase(upstreamCommitId).equals(upstreamCommitId),
                          "invalid upstreamCommitId: %s", upstreamCommitId);
        }
        this.upstreamCommitId = upstreamCommitId;
        this.publishUpstreamCommitTag = Boolean.TRUE.equals(publishUpstreamCommitTag);
        checkArgument(!this.publishUpstreamCommitTag || upstreamCommitId != null,
                      "publishUpstreamCommitTag requires upstreamCommitId");

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

    /**
     * Returns the SHA-1 of the upstream Git commit recorded for this commit, or {@code null} if none was
     * recorded.
     */
    // NON_NULL so that the replication log of a normal push is unchanged.
    @Nullable
    @JsonInclude(Include.NON_NULL)
    @JsonProperty
    public String upstreamCommitId() {
        return upstreamCommitId;
    }

    /**
     * Returns whether the upstream commit is published as a Git tag.
     */
    @JsonInclude(Include.NON_DEFAULT)
    @JsonProperty
    public boolean publishUpstreamCommitTag() {
        return publishUpstreamCommitTag;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof AbstractPushCommand)) {
            return false;
        }

        final AbstractPushCommand<?> that = (AbstractPushCommand<?>) obj;
        return super.equals(that) &&
               baseRevision.equals(that.baseRevision) &&
               summary.equals(that.summary) &&
               detail.equals(that.detail) &&
               markup == that.markup &&
               changes.equals(that.changes) &&
               Objects.equals(upstreamCommitId, that.upstreamCommitId) &&
               publishUpstreamCommitTag == that.publishUpstreamCommitTag;
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseRevision, summary, detail, markup, changes, upstreamCommitId,
                            publishUpstreamCommitTag) * 31 +
               super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        // Build a summary of changes to avoid overly long toString() result.
        final StringBuilder changesBuilder = new StringBuilder("[");
        for (int i = 0; i < changes.size(); i++) {
            final Change<?> change = changes.get(i);
            final String content = change.contentAsText();
            changesBuilder.append("{type: ").append(change.type())
                          .append(", path: ").append(change.path())
                          .append(", contentLength: ")
                          .append(content != null ? content.length() : 0)
                          .append('}');
            if (i != changes.size() - 1) {
                changesBuilder.append(", ");
            }
        }
        changesBuilder.append(']');

        return super.toStringHelper()
                    .add("baseRevision", baseRevision)
                    .add("summary", summary)
                    .add("detail", detail)
                    .add("markup", markup)
                    .add("changes", changesBuilder.toString());
    }
}
