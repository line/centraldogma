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

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;

/**
 * A {@link Command} which recovers a diverged repository from a source replica. It is originated by the
 * source replica (the single source of truth) and applied identically on every replica, itself included: a
 * replica already converged with {@link #commits()} is left untouched, and every other one resets its git
 * repository and commit-id database to {@link #resetToRevision()} and replays {@link #commits()} up to
 * {@link #headRevision()}. Because the replayed commits carry the original author, timestamp and
 * self-contained changes, a replay reproduces the source's commit ids; each one is verified against
 * {@link ReplayCommit#expectedCommitId()}, and a mismatch aborts the recovery and rolls the replica back.
 *
 * <p>The convergence check is by content, not by replica: the source is normally the replica that is
 * already converged, but a commit that lands on it between the payload build and the apply (a force push,
 * which read-only does not block) makes the source replay over itself too, discarding that commit.
 *
 * <p>This is a {@link RepositoryCommand} so that it is scoped to a single repository (lock scope and
 * read-only failure blast radius) and is not rejected while the repository/project is read-only.
 */
public final class RecoverRepositoryCommand extends RepositoryCommand<Revision> {

    private final int sourceServerId;
    private final Revision resetToRevision;
    private final Revision headRevision;
    private final List<ReplayCommit> commits;

    @JsonCreator
    RecoverRepositoryCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                             @JsonProperty("author") @Nullable Author author,
                             @JsonProperty("projectName") String projectName,
                             @JsonProperty("repositoryName") String repositoryName,
                             @JsonProperty("sourceServerId") int sourceServerId,
                             @JsonProperty("resetToRevision") Revision resetToRevision,
                             @JsonProperty("headRevision") Revision headRevision,
                             @JsonProperty("commits") Iterable<ReplayCommit> commits) {
        super(CommandType.RECOVER_REPOSITORY, timestamp, author, projectName, repositoryName);
        this.sourceServerId = sourceServerId;
        this.resetToRevision = requireNonNull(resetToRevision, "resetToRevision");
        this.headRevision = requireNonNull(headRevision, "headRevision");
        this.commits = ImmutableList.copyOf(requireNonNull(commits, "commits"));
    }

    /**
     * Returns the ZooKeeper server ID of the source replica whose repository the {@link #commits()} were
     * taken from. It records where a recovery came from; it is not consulted when the command is applied,
     * which decides by content (see the class javadoc).
     */
    @JsonProperty
    public int sourceServerId() {
        return sourceServerId;
    }

    /**
     * Returns the {@link Revision} to which a replica resets its repository before replaying
     * {@link #commits()}.
     */
    @JsonProperty
    public Revision resetToRevision() {
        return resetToRevision;
    }

    /**
     * Returns the head {@link Revision} of the source repository, which is also the result of this command.
     */
    @JsonProperty
    public Revision headRevision() {
        return headRevision;
    }

    /**
     * Returns the ordered {@link ReplayCommit}s to replay after resetting to {@link #resetToRevision()}.
     */
    @JsonProperty
    public List<ReplayCommit> commits() {
        return commits;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof RecoverRepositoryCommand)) {
            return false;
        }
        final RecoverRepositoryCommand that = (RecoverRepositoryCommand) obj;
        return super.equals(that) &&
               sourceServerId == that.sourceServerId &&
               resetToRevision.equals(that.resetToRevision) &&
               headRevision.equals(that.headRevision) &&
               commits.equals(that.commits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceServerId, resetToRevision, headRevision, commits) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("sourceServerId", sourceServerId)
                    .add("resetToRevision", resetToRevision)
                    .add("headRevision", headRevision)
                    .add("commits", commits.size());
    }
}
