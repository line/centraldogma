/*
 * Copyright 2020 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;

class GitRepositoryManagerTest {

    private static final String TEST_REPO = "test_repo";

    static Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path temp) {
        tempDir = temp;
    }

    @Test
    void testCreate() {
        final GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        final Repository repository = gitRepositoryManager.create(TEST_REPO, Author.SYSTEM);
        assertThat(repository).isInstanceOf(GitRepository.class);
        assertThat(((GitRepository) repository).cache).isNotNull();

        // Must disallow creating a duplicate.
        assertThatThrownBy(() -> gitRepositoryManager.create(TEST_REPO, Author.SYSTEM))
                .isInstanceOf(RepositoryExistsException.class);
    }

    @Test
    void testOpen() {
        // Create a new repository and close the manager.
        GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        gitRepositoryManager.create(TEST_REPO, Author.SYSTEM);
        gitRepositoryManager.close(() -> null);

        // Create a new manager so that it loads the repository we created above.
        gitRepositoryManager = newRepositoryManager();
        final Repository repository = gitRepositoryManager.get(TEST_REPO);
        assertThat(repository).isInstanceOf(GitRepository.class);
        assertThat(((GitRepository) repository).cache).isNotNull();
    }

    @Test
    void testGet() {
        final GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        assertThat(gitRepositoryManager.exists(TEST_REPO)).isFalse();

        final Repository repository = gitRepositoryManager.create(TEST_REPO, Author.SYSTEM);
        assertThat(repository).isInstanceOf(GitRepository.class);
        assertThat(gitRepositoryManager.get(TEST_REPO)).isSameAs(repository);
        assertThat(gitRepositoryManager.get(TEST_REPO)).isSameAs(repository);
    }

    @Test
    void testGetAndHas() {
        final GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        assertThat(gitRepositoryManager.exists(TEST_REPO)).isFalse();
        final Repository repo = gitRepositoryManager.create(TEST_REPO, Author.SYSTEM);
        assertThat(gitRepositoryManager.exists(TEST_REPO)).isTrue();
        assertThat(gitRepositoryManager.get(TEST_REPO)).isSameAs(repo);
    }

    @Test
    void testDelete() {
        final GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        gitRepositoryManager.create(TEST_REPO, Author.SYSTEM);
        assertThat(gitRepositoryManager.exists(TEST_REPO)).isTrue();
        gitRepositoryManager.remove(TEST_REPO);
        assertThatThrownBy(() -> gitRepositoryManager.remove(TEST_REPO))
                .isInstanceOf(RepositoryNotFoundException.class);
        assertThat(gitRepositoryManager.exists(TEST_REPO)).isFalse();
    }

    @Test
    void testList() {
        final GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        final int numRepoFiles = 1;
        final String repoNamePattern = "repo%d";
        for (int i = 0; i < numRepoFiles; i++) {
            final String targetRepoName = String.format(repoNamePattern, i);
            gitRepositoryManager.create(targetRepoName, Author.SYSTEM);
        }

        final int numDummyFiles = 1;
        for (int i = 0; i < numDummyFiles; i++) {
            final File file = Paths.get(tempDir.toString(), String.format("dummyDir%d", i)).toFile();
            if (!file.mkdirs()) {
                fail("failed to test on testList");
            }
            file.delete();
        }

        final Map<String, Repository> repoNameList = gitRepositoryManager.list();
        assertThat(repoNameList).hasSize(numRepoFiles);
    }

    @Test
    void testHas() {
        final GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        assertThat(gitRepositoryManager.exists(TEST_REPO)).isFalse();
        gitRepositoryManager.create(TEST_REPO, Author.SYSTEM);
        assertThat(gitRepositoryManager.exists(TEST_REPO)).isTrue();
        gitRepositoryManager.remove(TEST_REPO);
    }

    private GitRepositoryManager newRepositoryManager() {
        return new GitRepositoryManager(mock(Project.class), tempDir.toFile(),
                                        ForkJoinPool.commonPool(), MoreExecutors.directExecutor(),
                                        mock(RepositoryCache.class));
    }
}
