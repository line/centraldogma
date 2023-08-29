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

import static com.linecorp.centraldogma.server.internal.storage.DirectoryBasedStorageManager.SUFFIX_REMOVED;
import static com.linecorp.centraldogma.server.storage.repository.Repository.ALL_PATH;
import static java.util.concurrent.ForkJoinPool.commonPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.project.Project;

class GitRepositoryV2PromotionTest {

    @TempDir
    static File repoDir;

    @Test
    void promote() {
        final GitRepositoryV2 repo = new GitRepositoryV2(mock(Project.class),
                                                         new File(repoDir, "test_repo"), commonPool(),
                                                         0L, Author.SYSTEM, null);
        final InternalRepository primaryRepo = repo.primaryRepo;
        final CommitIdDatabase primaryCommitIdDatabase = primaryRepo.commitIdDatabase();
        assertThat(primaryCommitIdDatabase.firstRevision()).isEqualTo(primaryCommitIdDatabase.headRevision());
        addCommits(repo, 2, 11);
        assertThat(primaryCommitIdDatabase.headRevision().major()).isEqualTo(11);

        assertThat(repo.shouldCreateRollingRepository(10, 0)).isNull();
        // Nothing happened because 10 commits are made so far.
        assertThat(repo.secondaryRepo).isNull();

        // Add one more commit to create a rolling repository.
        addCommits(repo, 12, 12);
        assertThat(repo.shouldCreateRollingRepository(10, 0).major()).isEqualTo(12);
        repo.createRollingRepository(new Revision(12), 1, 0);

        assertThat(primaryCommitIdDatabase.headRevision().major()).isEqualTo(12);
        final InternalRepository secondaryRepo = repo.secondaryRepo;
        assertThat(secondaryRepo).isNotNull();
        assertThat(secondaryRepo.commitIdDatabase().firstRevision().major()).isEqualTo(12);
        assertThat(secondaryRepo.commitIdDatabase().headRevision().major()).isEqualTo(12);
        assertThat(secondaryRepo.secondCommitCreationTimeInstant()).isNull();

        // Add ten more commit.
        addCommits(repo, 13, 22);
        // No changes.
        assertThat(repo.shouldCreateRollingRepository(10, 0)).isNull();
        assertThat(primaryCommitIdDatabase.headRevision().major()).isEqualTo(22);
        assertThat(secondaryRepo).isEqualTo(repo.secondaryRepo);
        assertThat(repo.primaryRepo).isNotSameAs(repo.secondaryRepo);
        assertThat(secondaryRepo.commitIdDatabase().firstRevision().major()).isEqualTo(12);
        assertThat(secondaryRepo.commitIdDatabase().headRevision().major()).isEqualTo(22);
        assertThat(secondaryRepo.secondCommitCreationTimeInstant()).isEqualTo(Instant.ofEpochMilli(13 * 1000));

        // Add one more commit to create a rolling repository.
        addCommits(repo, 23, 23);
        assertThat(repo.shouldCreateRollingRepository(10, 0).major()).isEqualTo(23);
        repo.createRollingRepository(new Revision(23), 1, 0);
        // The secondary repo is promoted to the primary repo.
        assertThat(repo.primaryRepo).isSameAs(secondaryRepo);
        assertThat(repo.primaryRepo).isNotSameAs(primaryRepo);
        // The old primary repo is gone and renamed.
        await().until(() -> !primaryRepo.repoDir().exists());
        assertThat(new File(primaryRepo.repoDir() + SUFFIX_REMOVED).exists()).isTrue();

        final CommitIdDatabase newPrimaryDatabase = repo.primaryRepo.commitIdDatabase();
        assertThat(newPrimaryDatabase.firstRevision().major()).isEqualTo(12);
        assertThat(newPrimaryDatabase.headRevision().major()).isEqualTo(23);

        // A new secondary repo is created.
        assertThat(repo.secondaryRepo).isNotSameAs(secondaryRepo);
        assertThat(repo.secondaryRepo.commitIdDatabase().firstRevision().major()).isEqualTo(23);
        assertThat(repo.secondaryRepo.commitIdDatabase().headRevision().major()).isEqualTo(23);
        assertThat(repo.secondaryRepo.secondCommitCreationTimeInstant()).isNull();

        // Add 11 commits so that we are ready to create a rolling repository.
        addCommits(repo, 24, 35);
        assertThat(repo.shouldCreateRollingRepository(10, 0).major()).isEqualTo(35);

        // Add 5 more commits before creating a rolling repository to check if there are missing commits.
        addCommits(repo, 36, 40);
        repo.createRollingRepository(new Revision(35), 1, 0);

        assertThat(repo.primaryRepo.commitIdDatabase().firstRevision().major()).isEqualTo(23);
        assertThat(repo.primaryRepo.commitIdDatabase().headRevision().major()).isEqualTo(40);

        assertThat(repo.secondaryRepo.commitIdDatabase().firstRevision().major()).isEqualTo(35);
        assertThat(repo.secondaryRepo.commitIdDatabase().headRevision().major()).isEqualTo(40);

        for (int i = 36; i < 40; i++) {
            assertThat(entries(repo.primaryRepo, i)).containsExactlyInAnyOrderEntriesOf(
                    entries(repo.primaryRepo, i));
        }

        repo.internalClose();
    }

    static void addCommits(GitRepositoryV2 repo, int start, int end) {
        for (int i = start; i <= end; i++) {
            final Change<String> change = Change.ofTextUpsert("/file_" + i + ".txt", String.valueOf(i));
            addCommit(repo, i, change);
        }
    }

    static void addCommit(GitRepositoryV2 repo, int index, Change<String> change) {
        repo.commit(Revision.HEAD, index * 1000L, Author.SYSTEM,
                    "Summary" + index, "Detail", Markup.PLAINTEXT, change).join();
    }

    private static Map<String, Entry<?>> entries(InternalRepository repo, int revision) {
        return repo.find(new Revision(revision), ALL_PATH, ImmutableMap.of());
    }
}
