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
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.RecoverRepositoryCommand;
import com.linecorp.centraldogma.server.command.RecoverRepositoryRequestCommand;
import com.linecorp.centraldogma.server.command.ReplayCommit;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

/**
 * Builds a self-contained {@link RecoverRepositoryCommand} from the local storage. Invoked only on the
 * source replica of a recovery, whose repository is the single source of truth.
 */
public final class RecoveryPayloadBuilder {

    private final ProjectManager projectManager;

    /**
     * Creates a new instance.
     */
    public RecoveryPayloadBuilder(ProjectManager projectManager) {
        this.projectManager = requireNonNull(projectManager, "projectManager");
    }

    /**
     * Builds a {@link RecoverRepositoryCommand} that carries the commits of
     * {@code request.fromRevision()..HEAD} of the local repository, so that every other replica can
     * converge to the local history by replaying them.
     */
    public Command<Revision> build(RecoverRepositoryRequestCommand request) {
        requireNonNull(request, "request");
        return build(request.author(), request.projectName(), request.repositoryName(),
                     request.sourceServerId(), request.fromRevision());
    }

    /**
     * Builds a {@link RecoverRepositoryCommand} that carries the commits of {@code fromRevision..HEAD} of
     * the local repository. The reset revision and the head revision are derived here, so that a recovery
     * originated directly by the source replica and one originated in reaction to a request are built by
     * the same rules.
     */
    public Command<Revision> build(Author author, String projectName, String repositoryName,
                                   int sourceServerId, Revision fromRevision) {
        requireNonNull(author, "author");
        requireNonNull(projectName, "projectName");
        requireNonNull(repositoryName, "repositoryName");
        requireNonNull(fromRevision, "fromRevision");
        final List<ReplayCommit> commits =
                projectManager.get(projectName).repos().buildRecoveryPayload(repositoryName, fromRevision);
        final Revision headRevision = commits.get(commits.size() - 1).revision();
        return Command.recoverRepository(author, projectName, repositoryName, sourceServerId,
                                         fromRevision.backward(1), headRevision, commits);
    }
}
