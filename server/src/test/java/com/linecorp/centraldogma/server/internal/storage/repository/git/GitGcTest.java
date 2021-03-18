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
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.project.Project;

class GitGcTest {

    private static final Logger logger = LoggerFactory.getLogger(GitGcTest.class);

    @TempDir
    static File repoDir;

    private static GitRepository repo;

    @BeforeAll
    static void init() {
        repo = new GitRepository(mock(Project.class), new File(repoDir, "test_repo"),
                                 commonPool(), 0L, Author.SYSTEM);
    }

    private static String randString() {
        final byte[] bytes = new byte[1000];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    @Test
    void parallelRunGcWithCommit() throws Exception {
        // Generate huge commits for long gc time.
        for (int i = 0; i < 10_000; i++) {
            final Change<String> change = Change.ofTextUpsert("/foo", randString());
            repo.commit(Revision.HEAD, 0L, Author.UNKNOWN, "summary", change).join();
        }

        final ExecutorService gcExecutor = Executors.newSingleThreadExecutor();
        final CountDownLatch startLatch = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean();

        // Execute gc in a separate thread.
        gcExecutor.execute(() -> {
            try {
                startLatch.countDown();
                repo.gc();
                completed.set(true);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        });

        startLatch.await();
        for (int i = 0; i < 10; i++) {
            final Change<String> change = Change.ofTextUpsert("/foo", randString());
            repo.commit(Revision.HEAD, 0L, Author.UNKNOWN, "summary", change).join();
        }
        // Make sure that a gc is still running.
        // This is flaky. But a gc for 10k commits usually takes about more than 15 seconds.
        assertThat(completed).isFalse();

        await().timeout(Duration.ofMinutes(3)).untilTrue(completed);
    }
}
