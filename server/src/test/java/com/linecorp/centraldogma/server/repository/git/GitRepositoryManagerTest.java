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

package com.linecorp.centraldogma.server.repository.git;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
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

import com.linecorp.centraldogma.server.common.StorageExistsException;
import com.linecorp.centraldogma.server.project.Project;
import com.linecorp.centraldogma.server.repository.Repository;

public class GitRepositoryManagerTest {

    private static final String TEST_REPO = "test_repo";

    @Rule
    public final TemporaryFolder rootDir = new TemporaryFolder();

    File rootDir() {
        return rootDir.getRoot();
    }

    /**
     * Create a {@link Repository} on existing directory will result exception.
     */
    @Test
    public void testCreate() throws Exception {
        GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        Repository repository = gitRepositoryManager.create(TEST_REPO);
        assertTrue(repository instanceof GitRepository);

        try {
            gitRepositoryManager.create(TEST_REPO);
            fail();
        } catch (StorageExistsException ignored) {
            // Expected
        }
    }

    @Test
    public void testGet() throws Exception {
        GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        assertThat(gitRepositoryManager.exists(TEST_REPO), is(false));

        Repository repository = gitRepositoryManager.create(TEST_REPO);
        assertTrue(repository instanceof GitRepository);
        assertSame(repository, gitRepositoryManager.get(TEST_REPO));
        assertSame(repository, gitRepositoryManager.get(TEST_REPO));
    }

    @Test
    public void testGetAndHas() {
        GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        assertFalse(gitRepositoryManager.exists(TEST_REPO));
        Repository repo = gitRepositoryManager.create(TEST_REPO);
        assertTrue(gitRepositoryManager.exists(TEST_REPO));
        assertSame(repo, gitRepositoryManager.get(TEST_REPO));
    }

    @Test
    public void testDelete() throws Exception {
        GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        gitRepositoryManager.create(TEST_REPO);
        assertTrue(gitRepositoryManager.exists(TEST_REPO));
        gitRepositoryManager.remove(TEST_REPO);
        try {
            gitRepositoryManager.remove(TEST_REPO);
            fail();
        } catch (Exception ignored) {
            // Expected
        }
        assertTrue(!gitRepositoryManager.exists(TEST_REPO));
    }

    @Test
    public void testList() {
        GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        int numRepoFiles = 1;
        String repoNamePattern = "repo%d";
        for (int i = 0; i < numRepoFiles; i++) {
            String targetRepoName = String.format(repoNamePattern, i);
            gitRepositoryManager.create(targetRepoName);
        }

        int numDummyFiles = 1;
        for (int i = 0; i < numDummyFiles; i++) {
            if (!Paths.get(rootDir.getRoot().toString(), String.format("dummyDir%d", i)).toFile().mkdirs()) {
                fail("failed to test on testList");
            }
        }

        Map<String, Repository> repoNameList = gitRepositoryManager.list();
        assertEquals(numRepoFiles, repoNameList.size());
    }

    @Test
    public void testHas() throws IOException {
        GitRepositoryManager gitRepositoryManager = newRepositoryManager();
        assertTrue(!gitRepositoryManager.exists(TEST_REPO));
        gitRepositoryManager.create(TEST_REPO);
        assertTrue(gitRepositoryManager.exists(TEST_REPO));
        gitRepositoryManager.remove(TEST_REPO);
    }

    private GitRepositoryManager newRepositoryManager() {
        return new GitRepositoryManager(mock(Project.class), rootDir(), ForkJoinPool.commonPool());
    }
}
