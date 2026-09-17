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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Revision;

/**
 * A {@link Command} which recovers a diverged repository from a source replica. It is originated by the
 * source replica (the single source of truth) and applied identically on every replica, itself included: a
 * replica already converged with {@link #commits()} is left untouched, and every other one resets its git
 * repository and commit-id database to the revision before the first commit and replays {@link #commits()}.
 * The list includes empty compatibility commits after the selected source history.
 * Because the changes are self-contained, a replay reproduces the source's content;
 * the tree of each replayed commit is verified against {@link ReplayCommit#expectedTreeId()}, and a
 * mismatch aborts the recovery, leaving that replica with a partial history until it is recovered again.
 * The commit ID is deliberately not what is verified: it covers the parent and the timestamp too, and a
 * metadata repository writes its early commits locally on each replica, so replicas holding identical
 * content still report different commit IDs.
 *
 * <p>The convergence check is by content, not by replica. A non-converged replica whose head has reached
 * the final payload revision is rejected instead of rewound.
 *
 * <p>This is a {@link RepositoryCommand} so that it is scoped to a single repository (lock scope and
 * read-only failure blast radius) and is not rejected while the repository/project is read-only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ApplyRepositoryRecoveryCommand extends RepositoryCommand<Revision> {

    /**
     * The most revisions a single recovery may replay, including compatibility padding.
     */
    public static final int MAX_RECOVERY_COMMITS = 100;

    private final List<ReplayCommit> commits;

    @JsonCreator
    ApplyRepositoryRecoveryCommand(@JsonProperty("timestamp") @Nullable Long timestamp,
                                   @JsonProperty("author") @Nullable Author author,
                                   @JsonProperty("projectName") String projectName,
                                   @JsonProperty("repositoryName") String repositoryName,
                                   @JsonProperty("commits") Iterable<ReplayCommit> commits) {
        super(CommandType.APPLY_REPOSITORY_RECOVERY, timestamp, author, projectName, repositoryName);
        this.commits = ImmutableList.copyOf(requireNonNull(commits, "commits"));
        checkArgument(!this.commits.isEmpty(), "commits is empty");
        checkArgument(this.commits.size() <= MAX_RECOVERY_COMMITS,
                      "commits: %s (expected: <= %s)", this.commits.size(), MAX_RECOVERY_COMMITS);
        final Revision firstRevision = this.commits.get(0).revision();
        checkArgument(!firstRevision.isRelative() && firstRevision.major() > Revision.INIT.major(),
                      "commits[0].revision: %s (expected: an absolute revision > %s)",
                      firstRevision, Revision.INIT);
        checkArgument((long) firstRevision.major() + this.commits.size() - 1 <= Integer.MAX_VALUE,
                      "commits exceed the maximum revision: %s", Integer.MAX_VALUE);
        for (int i = 0; i < this.commits.size(); i++) {
            final Revision expectedRevision = firstRevision.forward(i);
            checkArgument(expectedRevision.equals(this.commits.get(i).revision()),
                          "commits[%s].revision: %s (expected: %s)",
                          i, this.commits.get(i).revision(), expectedRevision);
        }
    }

    /**
     * Returns the ordered {@link ReplayCommit}s to replay after resetting to the preceding revision.
     */
    @JsonProperty("commits")
    public List<ReplayCommit> commits() {
        return commits;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ApplyRepositoryRecoveryCommand)) {
            return false;
        }
        final ApplyRepositoryRecoveryCommand that = (ApplyRepositoryRecoveryCommand) obj;
        return super.equals(that) &&
               commits.equals(that.commits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commits) * 31 + super.hashCode();
    }

    @Override
    ToStringHelper toStringHelper() {
        return super.toStringHelper()
                    .add("commits", commits.size());
    }
}
