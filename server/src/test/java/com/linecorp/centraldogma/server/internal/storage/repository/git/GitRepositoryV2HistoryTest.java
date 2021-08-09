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

import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryV2PromotionTest.addCommits;
import static java.util.concurrent.ForkJoinPool.commonPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.RevisionNotFoundException;
import com.linecorp.centraldogma.server.storage.project.Project;

class GitRepositoryV2HistoryTest {

    @TempDir
    static File repoDir;
    private static GitRepositoryV2 repo;

    @BeforeAll
    static void setUp() {
        // The repository contains commits from 10 to 20(inclusive).
        repo = createRepository(repoDir, 10, 20);
    }

    @AfterAll
    static void tearDown() {
        repo.internalClose();
    }

    @Test
    void invalidRevision() {
        // The larger of the two revisions should be greater or equal to the firstRevision.
        assertThatThrownBy(() -> repo.history(new Revision(2), new Revision(9), "/**")
                                     .join())
                .hasCauseInstanceOf(RevisionNotFoundException.class)
                .hasMessageContaining("revision: Revision(2) (expected: >= 10)");
        assertThatThrownBy(() -> repo.history(new Revision(9), new Revision(2), "/**")
                                     .join())
                .hasCauseInstanceOf(RevisionNotFoundException.class)
                .hasMessageContaining("revision: Revision(9) (expected: >= 10)");

        // The larger of the two revisions should be lower or equal to the headRevision.
        assertThatThrownBy(() -> repo.history(new Revision(2), new Revision(21), "/**")
                                     .join())
                .hasCauseInstanceOf(RevisionNotFoundException.class)
                .hasMessageContaining("revision: Revision(2) (expected: >= 10)");
        assertThatThrownBy(() -> repo.history(new Revision(21), new Revision(2), "/**")
                                     .join())
                .hasCauseInstanceOf(RevisionNotFoundException.class)
                .hasMessageContaining("revision: Revision(21) (expected: <= 20)");

        assertThatThrownBy(() -> repo.history(new Revision(2), new Revision(10), "/**")
                                     .join())
                .hasCauseInstanceOf(RevisionNotFoundException.class)
                .hasMessageContaining("revision: Revision(2) (expected: >= 10)");

        assertThatThrownBy(() -> repo.history(new Revision(10), new Revision(2), "/**")
                                     .join())
                .hasCauseInstanceOf(RevisionNotFoundException.class)
                .hasMessageContaining("revision: Revision(2) (expected: >= 10)");
    }

    @Test
    void normalHistoryCall() {
        List<Commit> commits = repo.history(new Revision(10), new Revision(14), "/**")
                                         .join();
        assertThat(commits).hasSize(5);

        commits = repo.history(Revision.INIT, Revision.INIT, "/**").join();
        System.err.println(commits);
        assertThat(commits).hasSize(1);
    }

    static GitRepositoryV2 createRepository(File repoDir, int from, int to) {
        final GitRepositoryV2 repo = new GitRepositoryV2(mock(Project.class),
                                                         new File(repoDir, "test_repo"), commonPool(),
                                                         0L, Author.SYSTEM, null);
        addCommits(repo, 2, from);
        repo.removeOldCommits(from - 2, 0);
        // The headRevision of the secondary repository is now from revision.

        addCommits(repo, from + 1, to);
        repo.removeOldCommits(to - from - 1, 0);

        final CommitIdDatabase commitIdDatabase = repo.primaryRepo.commitIdDatabase();
        assertThat(commitIdDatabase.firstRevision().major()).isSameAs(10);
        assertThat(commitIdDatabase.headRevision().major()).isSameAs(20);
        return repo;
    }
}
