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

import static com.linecorp.centraldogma.common.Revision.HEAD;
import static java.util.concurrent.ForkJoinPool.commonPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.project.Project;

class GitGcTimeoutTest {

    @TempDir
    static File repoDir;

    private static GitRepository repo;

    @BeforeAll
    static void init() {
        repo = new GitRepository(mock(Project.class), new File(repoDir, "test_repo"),
                                 commonPool(), 0L, Author.SYSTEM);
    }

    @AfterAll
    static void destroy() {
        if (repo != null) {
            repo.internalClose();
        }
    }

    @Test
    void writeLockWithDirectExecution() {
        repo.gcLock.lock();
        final AtomicBoolean completed = new AtomicBoolean();
        commonPool().execute(() -> {
            final boolean acquired = repo.writeLock(true);
            assertThat(acquired).isFalse();
            completed.set(true);
        });
        await().timeout(Duration.ofSeconds(20)).untilTrue(completed);
        repo.gcLock.unlock();
    }

    @Test
    void writeLockWithReplicationLog() throws InterruptedException {
        repo.gcLock.lock();
        final AtomicBoolean completed = new AtomicBoolean();
        commonPool().execute(() -> {
            // Should wait until gcLock is released.
            final boolean acquired = repo.writeLock(false);
            assertThat(acquired).isTrue();
            completed.set(true);
        });

        // Wait more than 10 seconds for testing the lock timeout.
        Thread.sleep(20000);
        assertThat(completed).isFalse();

        repo.gcLock.unlock();
        await().untilTrue(completed);
    }

    @Test
    void shouldNotBlockReadWhileGc() {
        Change<String> change = Change.ofTextUpsert("/foo.txt", "bar");
        final Revision revision1 = repo.commit(HEAD, 0L, Author.UNKNOWN, "summary", change).join().revision();
        change = Change.ofTextUpsert("/foo.txt", "qux");
        final Revision revision2 = repo.commit(HEAD, 0L, Author.UNKNOWN, "summary", change).join().revision();
        repo.gcLock.lock();
        final Revision latestRevision = repo.findLatestRevision(revision1, "/foo.txt", false).join();
        assertThat(latestRevision).isEqualTo(revision2);
        repo.gcLock.unlock();
    }
}
