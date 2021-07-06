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
package com.linecorp.centraldogma.server.internal.storage.repository.git;

import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryUtil.closeJGitRepo;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryUtil.getCommits;
import static com.linecorp.centraldogma.server.storage.repository.Repository.ALL_PATH;

import java.io.File;
import java.time.Instant;

import javax.annotation.Nullable;

import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionRange;
import com.linecorp.centraldogma.server.storage.project.Project;

final class InternalRepository {

    private static final Logger logger = LoggerFactory.getLogger(InternalRepository.class);

    private final Project project;
    private final String originalRepoName; // e.g. foo
    private final File repoDir;            // e.g. foo_0000000000
    private final Repository jGitRepository;
    private final CommitIdDatabase commitIdDatabase;

    // Only accessed by the worker in CommitRetentionManagementPlugin.
    @Nullable
    private Instant secondCommitCreationTimeInstant;

    InternalRepository(Project project, String originalRepoName, File repoDir,
                       Repository jGitRepository, CommitIdDatabase commitIdDatabase) {
        this.project = project;
        this.originalRepoName = originalRepoName;
        this.repoDir = repoDir;
        this.jGitRepository = jGitRepository;
        this.commitIdDatabase = commitIdDatabase;
    }

    Project project() {
        return project;
    }

    String originalRepoName() {
        return originalRepoName;
    }

    File repoDir() {
        return repoDir;
    }

    Repository jGitRepo() {
        return jGitRepository;
    }

    CommitIdDatabase commitIdDatabase() {
        return commitIdDatabase;
    }

    @Nullable
    Instant secondCommitCreationTimeInstant() {
        if (secondCommitCreationTimeInstant == null) {
            final Revision firstRevision = commitIdDatabase.firstRevision();
            if (firstRevision == null) {
                return null;
            }
            final Revision headRevision = commitIdDatabase.headRevision();
            if (headRevision == null) {
                return null;
            }
            if (firstRevision.equals(headRevision)) {
                // The second commit is not made yet.
                return null;
            }
            final Revision secondRevision = firstRevision.forward(1);
            final RevisionRange range = new RevisionRange(secondRevision, secondRevision);
            secondCommitCreationTimeInstant =
                    Instant.ofEpochMilli(getCommits(this, ALL_PATH, 1, range, range).get(0).when());
        }
        return secondCommitCreationTimeInstant;
    }

    void close() {
        try {
            commitIdDatabase.close();
        } catch (Throwable t) {
            logger.warn("Failed to close a commitId database:", t);
        }
        closeJGitRepo(jGitRepository);
    }
}
