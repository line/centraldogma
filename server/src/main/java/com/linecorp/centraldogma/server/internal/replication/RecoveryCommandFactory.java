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

package com.linecorp.centraldogma.server.internal.replication;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.ApplyRepositoryRecoveryCommand;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.ReplayCommit;
import com.linecorp.centraldogma.server.command.RequestRepositoryRecoveryCommand;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;

/**
 * Creates a self-contained {@link ApplyRepositoryRecoveryCommand} from the local storage. Invoked only on the
 * source replica of a recovery, whose repository is the single source of truth.
 */
public final class RecoveryCommandFactory {

    private static final long PADDING_COMMIT_TIMESTAMP_MILLIS = 0;
    private static final String PADDING_COMMIT_SUMMARY = "Recovery padding";

    private final ProjectManager projectManager;

    /**
     * Creates a new instance.
     */
    public RecoveryCommandFactory(ProjectManager projectManager) {
        this.projectManager = requireNonNull(projectManager, "projectManager");
    }

    Command<Revision> blockingNewCommand(RequestRepositoryRecoveryCommand request) {
        requireNonNull(request, "request");
        return blockingNewCommand(request.author(), request.projectName(), request.repositoryName(),
                                  request.fromRevision(), request.toRevision(), request.maxRevision());
    }

    Command<Revision> blockingNewCommand(Author author, String projectName, String repositoryName,
                                         Revision fromRevision, Revision toRevision, int maxRevision) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repositoryName, "repositoryName");
        requireNonNull(fromRevision, "fromRevision");
        requireNonNull(toRevision, "toRevision");
        checkArgument(maxRevision >= toRevision.major(),
                      "maxRevision: %s (expected: >= toRevision %s)", maxRevision, toRevision);
        checkArgument(maxRevision < Integer.MAX_VALUE,
                      "maxRevision: %s (expected: < %s)", maxRevision, Integer.MAX_VALUE);
        final RepositoryManager repositories = projectManager.get(projectName).repos();
        final Revision sourceHead = repositories.get(repositoryName).head().revision();
        if (sourceHead.compareTo(new Revision(maxRevision)) > 0) {
            throw new IllegalStateException(
                    "cannot recover " + projectName + '/' + repositoryName +
                    ": source head " + sourceHead + " exceeds maxRevision " + maxRevision);
        }
        final long recoveryCommitCount = (long) maxRevision - fromRevision.major() + 2;
        checkArgument(recoveryCommitCount <= ApplyRepositoryRecoveryCommand.MAX_RECOVERY_COMMITS,
                      "recovery spans too many revisions: %s (maximum: %s)", recoveryCommitCount,
                      ApplyRepositoryRecoveryCommand.MAX_RECOVERY_COMMITS);
        final List<ReplayCommit> sourceCommits =
                repositories.buildRecoveryPayload(repositoryName, fromRevision, toRevision);
        final Revision recoveryRevision = new Revision(maxRevision + 1);
        final ImmutableList.Builder<ReplayCommit> commits =
                ImmutableList.builderWithExpectedSize((int) recoveryCommitCount);
        commits.addAll(sourceCommits);

        final String expectedTreeId = sourceCommits.get(sourceCommits.size() - 1).expectedTreeId();
        // Advance past the supplied maximum revision so clients can resume watches from old absolute cursors.
        for (Revision revision = toRevision.forward(1);; revision = revision.forward(1)) {
            commits.add(new ReplayCommit(revision, PADDING_COMMIT_TIMESTAMP_MILLIS, Author.SYSTEM,
                                         PADDING_COMMIT_SUMMARY, "", Markup.PLAINTEXT,
                                         ImmutableList.of(), expectedTreeId));
            if (revision.equals(recoveryRevision)) {
                break;
            }
        }
        return Command.applyRepositoryRecovery(author, projectName, repositoryName, commits.build());
    }
}
