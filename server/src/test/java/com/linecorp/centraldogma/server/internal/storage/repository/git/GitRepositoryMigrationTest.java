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

import static java.util.concurrent.ForkJoinPool.commonPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Commit;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.project.Project;

class GitRepositoryMigrationTest {

    @TempDir
    static File repoDir;

    @Test
    void migrate() {
        final GitRepository oldRepo = new GitRepository(mock(Project.class), new File(repoDir, "test_repo"),
                                                        commonPool(), 0L, Author.SYSTEM);
        addCommits(oldRepo);
        oldRepo.internalClose();
        final GitRepositoryV2 migrated = GitRepositoryV2.open(mock(Project.class),
                                                              new File(repoDir, "test_repo"), commonPool(),
                                                              null);
        final List<Commit> commits = migrated.history(Revision.INIT, Revision.HEAD, "/**").join();
        assertThat(commits.get(0).summary()).contains("new repository");
        for (int i = 1; i < 10; i++) {
            assertThat(commits.get(i).summary()).isEqualTo("Summary" + i);
        }
    }

    private static void addCommits(GitRepository oldRepo) {
        for (int i = 1; i < 10; i++) {
            oldRepo.commit(Revision.HEAD, 0, Author.SYSTEM,
                           "Summary" + i, "Detail", Markup.PLAINTEXT,
                           Change.ofTextUpsert("/file_" + i + ".txt", String.valueOf(i))).join();
        }
    }
}
