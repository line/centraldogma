/*
 * Copyright 2017 LINE Corporation
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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.repository.RepositoryCache;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;

public class GitRepositoryManagerTest {

    private static final String TEST_REPO = "test_repo";

    @Rule
    public final TemporaryFolder rootDir = new TemporaryFolder();

    File rootDir() {
        return rootDir.getRoot();
    }

    @Test
    public void testCreate() throws Exception {
        final GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        final Repository repository = gitRepositoryManager.create(TEST_REPO, Author.SYSTEM);
        assertThat(repository).isInstanceOf(GitRepository.class);
        assertThat(((GitRepository) repository).cache).isNotNull();

        // Must disallow creating a duplicate.
        assertThatThrownBy(() -> gitRepositoryManager.create(TEST_REPO, Author.SYSTEM))
                .isInstanceOf(RepositoryExistsException.class);
    }

    @Test
    public void testOpen() throws Exception {
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
    public void testGet() throws Exception {
        final GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        assertThat(gitRepositoryManager.exists(TEST_REPO)).isFalse();

        final Repository repository = gitRepositoryManager.create(TEST_REPO, Author.SYSTEM);
        assertThat(repository).isInstanceOf(GitRepository.class);
        assertThat(gitRepositoryManager.get(TEST_REPO)).isSameAs(repository);
        assertThat(gitRepositoryManager.get(TEST_REPO)).isSameAs(repository);
    }

    @Test
    public void testGetAndHas() {
        final GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        assertThat(gitRepositoryManager.exists(TEST_REPO)).isFalse();
        final Repository repo = gitRepositoryManager.create(TEST_REPO, Author.SYSTEM);
        assertThat(gitRepositoryManager.exists(TEST_REPO)).isTrue();
        assertThat(gitRepositoryManager.get(TEST_REPO)).isSameAs(repo);
    }

    @Test
    public void testDelete() throws Exception {
        final GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        gitRepositoryManager.create(TEST_REPO, Author.SYSTEM);
        assertThat(gitRepositoryManager.exists(TEST_REPO)).isTrue();
        gitRepositoryManager.remove(TEST_REPO);
        assertThatThrownBy(() -> gitRepositoryManager.remove(TEST_REPO))
                .isInstanceOf(RepositoryNotFoundException.class);
        assertThat(gitRepositoryManager.exists(TEST_REPO)).isFalse();
    }

    @Test
    public void testList() {
        final GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        final int numRepoFiles = 1;
        final String repoNamePattern = "repo%d";
        for (int i = 0; i < numRepoFiles; i++) {
            final String targetRepoName = String.format(repoNamePattern, i);
            gitRepositoryManager.create(targetRepoName, Author.SYSTEM);
        }

        final int numDummyFiles = 1;
        for (int i = 0; i < numDummyFiles; i++) {
            if (!Paths.get(rootDir.getRoot().toString(), String.format("dummyDir%d", i)).toFile().mkdirs()) {
                fail("failed to test on testList");
            }
        }

        final Map<String, Repository> repoNameList = gitRepositoryManager.list();
        assertThat(repoNameList).hasSize(numRepoFiles);
    }

    @Test
    public void testHas() throws IOException {
        final GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        assertThat(gitRepositoryManager.exists(TEST_REPO)).isFalse();
        gitRepositoryManager.create(TEST_REPO, Author.SYSTEM);
        assertThat(gitRepositoryManager.exists(TEST_REPO)).isTrue();
        gitRepositoryManager.remove(TEST_REPO);
    }

    private GitRepositoryManager newRepositoryManager() {
        return new GitRepositoryManager(mock(Project.class), rootDir(), ForkJoinPool.commonPool(),
                                        MoreExecutors.directExecutor(), mock(RepositoryCache.class));
    }
}
