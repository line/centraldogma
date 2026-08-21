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

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ReplicationStatus;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.RecoverRepositoryCommand;
import com.linecorp.centraldogma.server.command.RecoverRepositoryRequestCommand;
import com.linecorp.centraldogma.server.command.ReplayCommit;
import com.linecorp.centraldogma.server.internal.management.RepoStatusManager;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

/**
 * Builds a self-contained {@link RecoverRepositoryCommand} from the local storage. Invoked only on the
 * source replica of a recovery, whose repository is the single source of truth.
 */
public final class RecoveryPayloadBuilder {

    private final ProjectManager projectManager;
    private final RepoStatusManager repoStatusManager;

    /**
     * Creates a new instance.
     */
    public RecoveryPayloadBuilder(ProjectManager projectManager, RepoStatusManager repoStatusManager) {
        this.projectManager = requireNonNull(projectManager, "projectManager");
        this.repoStatusManager = requireNonNull(repoStatusManager, "repoStatusManager");
    }

    /**
     * Builds a {@link RecoverRepositoryCommand} that carries the commits of
     * {@code request.fromRevision()..request.toRevision()} of the local repository, so that every replica
     * can converge to that history by replaying them.
     */
    public Command<Revision> build(RecoverRepositoryRequestCommand request) {
        requireNonNull(request, "request");
        return build(request.author(), request.projectName(), request.repositoryName(),
                     request.sourceServerId(), request.fromRevision(), request.toRevision());
    }

    /**
     * Builds a {@link RecoverRepositoryCommand} that carries the commits of
     * {@code fromRevision..toRevision} of the local repository. The reset revision is derived here, so that
     * a recovery originated directly by the source replica and one originated in reaction to a request are
     * built by the same rules.
     */
    public Command<Revision> build(Author author, String projectName, String repositoryName,
                                   int sourceServerId, Revision fromRevision, Revision toRevision) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repositoryName, "repositoryName");
        requireNonNull(fromRevision, "fromRevision");
        requireNonNull(toRevision, "toRevision");
        // A request travels through the replication log, so an arbitrary amount of time can pass before the
        // source builds the payload. Recover only what is still frozen: a repository made writable again has
        // moved on, and rewriting it would discard the commits it took since.
        final ReplicationStatus status = repoStatusManager.replicationStatus(projectName, repositoryName);
        if (status != ReplicationStatus.READ_ONLY) {
            throw new IllegalStateException(
                    "cannot recover " + projectName + '/' + repositoryName + ": the repository is " +
                    status + " (expected: " + ReplicationStatus.READ_ONLY + ')');
        }
        final List<ReplayCommit> commits =
                projectManager.get(projectName).repos()
                              .buildRecoveryPayload(repositoryName, fromRevision, toRevision);
        return Command.recoverRepository(author, projectName, repositoryName, sourceServerId,
                                         fromRevision.backward(1), toRevision, commits);
    }
}
