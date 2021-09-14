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

import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryV2HistoryTest.createRepository;
import static com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryV2PromotionTest.addCommit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;

class GitRepositoryV2WatchTest {

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
    void testWatch() {
        assertThat(repo.watch(Revision.INIT, "/file_2.txt").join()).isEqualTo(new Revision(20));
        // Return the head revision even though there was no change for "/file_2.txt" which is committed at
        // the revision 2. The current primary repo does not know if there was a commit for the entry between
        // the specified(5) revision and the first revision(10) of the repo. So it's safe to return the
        // head revision.
        assertThat(repo.watch(new Revision(5), "/file_2.txt").join()).isEqualTo(new Revision(20));
        assertThat(repo.watch(new Revision(5), "/file_6.txt").join()).isEqualTo(new Revision(20));

        final CompletableFuture<Revision> future = repo.watch(new Revision(10), "/file_2.txt");
        assertThat(future.isDone()).isFalse();
        addCommit(repo, 21, Change.ofTextUpsert("/file_2.txt", String.valueOf(2 + 2)));

        assertThat(future.join()).isEqualTo(new Revision(21));
        // Make sure CommitWatchers has cleared the watch.
        await().untilAsserted(() -> assertThat(repo.commitWatchers.watchesMap).isEmpty());
    }
}
