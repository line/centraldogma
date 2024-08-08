/*
 * Copyright 2024 LINE Corporation
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
import java.time.Instant;
import java.util.Map;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.RepositoryListener;

class GitRepositoryListenerTest {

    @TempDir
    static File repoDir;

    private static GitRepository repo;
    private static TestRepositoryListener listener;

    @BeforeAll
    static void init() {
        repo = new GitRepository(mock(Project.class), new File(repoDir, "test_repo"),
                                 commonPool(), 0L, Author.SYSTEM);
        listener = new TestRepositoryListener();
        repo.addListener(listener);
    }

    @AfterAll
    static void destroy() {
        if (repo != null) {
            repo.internalClose();
        }
    }

    @Test
    void shouldUpdateLatestEntries() {
        commit(Change.ofTextUpsert("/foo.txt", "bar"));
        assertThat(listener.latestEntries).isNull();

        // Add
        final String pathA = "/listenable/a.txt";
        for (int i = 0; i < 5; i++) {
            final String text = "a" + i;
            commit(Change.ofTextUpsert(pathA, text));
            assertListenerEntries(pathA, text);
        }

        final String pathB = "/listenable/b.txt";
        for (int i = 0; i < 5; i++) {
            final String text = "b" + i;
            commit(Change.ofTextUpsert(pathB, text));
            assertListenerEntries(pathB, text);
            assertListenerEntries(pathA, "a4");
        }

        // Rename
        final String pathC = "/listenable/c.txt";
        commit(Change.ofRename(pathA, pathC));
        assertListenerEntries(pathC, "a4");
        assertListenerEntries(pathB, "b4");

        // Remove
        commit(Change.ofRemoval(pathB));
        assertListenerEntries(pathC, "a4");
        assertThat(listener.latestEntries).hasSize(1);
    }

    private void assertListenerEntries(String path, String expected) {
        await().untilAsserted(() -> {
            assertThat(listener.latestEntries.get(path).contentAsText().trim())
                    .isEqualTo(expected);
        });
    }

    private void commit(Change<?>... changes) {
        repo.commit(Revision.HEAD, Instant.now().toEpochMilli(), Author.SYSTEM, "summary", changes).join();
    }

    private static final class TestRepositoryListener implements RepositoryListener {

        @Nullable
        private volatile Map<String, Entry<?>> latestEntries;

        @Override
        public String pathPattern() {
            return "/listenable/**";
        }

        @Override
        public void onUpdate(Map<String, Entry<?>> entries) {
            latestEntries = entries;
        }
    }
}
