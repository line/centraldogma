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

package com.linecorp.centraldogma.server.internal.storage.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryManager;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;

public class RepositoryManagerWrapperTest {

    @ClassRule
    public static final TemporaryFolder rootDir = new TemporaryFolder();

    private static RepositoryManager m;

    @Rule
    public final TestName testName = new TestName();

    private Executor purgeWorker;

    @Before
    public void init() throws IOException {
        purgeWorker = mock(Executor.class);
        m = new RepositoryManagerWrapper(new GitRepositoryManager(mock(Project.class), rootDir.getRoot(),
                                                                  ForkJoinPool.commonPool(),
                                                                  purgeWorker, null),
                                         RepositoryWrapper::new);
    }

    @Test
    public void testCreate() {
        final String name = testName.getMethodName();
        final Repository repo = m.create(name, Author.SYSTEM);
        assertThat(repo).isInstanceOf(RepositoryWrapper.class);
        // The cached result will be returned on the second call.
        assertThatThrownBy(() -> m.create(name, Author.SYSTEM)).isInstanceOf(RepositoryExistsException.class);
    }

    @Test
    public void testGet() {
        final String name = testName.getMethodName();
        final Repository repo = m.create(name, Author.SYSTEM);
        final Repository repo2 = m.get(name);

        // Check if the reference is same.
        assertThat(repo).isSameAs(repo2);
    }

    @Test
    public void testRemove() {
        final String name = testName.getMethodName();
        m.create(name, Author.SYSTEM);
        m.remove(name);
        assertThat(m.exists(name)).isFalse();
    }

    @Test
    public void testRemove_failure() {
        final String name = testName.getMethodName();
        assertThatThrownBy(() -> m.remove(name)).isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    public void testUnRemove_failure() {
        final String name = testName.getMethodName();
        assertThatThrownBy(() -> m.unremove(name)).isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    public void testList() {
        final String name = testName.getMethodName();
        final int numNames = 10;
        for (int i = 0; i < numNames; i++) {
            m.create(name + i, Author.SYSTEM);
        }
        final List<String> names = m.list().entrySet().stream()
                                    .map(Map.Entry::getKey)
                                    .collect(Collectors.toList());

        // Check if names is in ascending order
        for (int i = 0; i < numNames - 1; i++) {
            if (names.get(i).compareTo(names.get(i + 1)) > 0) {
                fail("names is not in ascending order");
            }
        }
    }

    @Test
    public void testMarkForPurge() {
        final String name = testName.getMethodName();
        m.create(name, Author.SYSTEM);
        m.remove(name);
        m.markForPurge(name);
        verify(purgeWorker).execute(any());
        assertThat(m.listRemoved().keySet()).doesNotContain(name);
    }

    @Test
    public void testPurgeMarked() {
        final String name = testName.getMethodName();
        final int numNames = 10;
        for (int i = 0; i < numNames; i++) {
            String targetName = name + i;
            m.create(targetName, Author.SYSTEM);
            m.remove(targetName);
            m.markForPurge(targetName);
        }
        m.purgeMarked();
        for (int i = 0; i < numNames; i++) {
            String targetName = name + i;
            assertThatThrownBy(() -> m.get(targetName)).isInstanceOf(RepositoryNotFoundException.class);
        }
    }

    @Test
    public void testClose() {
        final String name = testName.getMethodName();
        m.create(name, Author.SYSTEM);
        assertTrue(m.exists(name));
        m.close(ShuttingDownException::new);

        assertThatThrownBy(() -> m.get(name)).isInstanceOf(ShuttingDownException.class);
        assertThatThrownBy(() -> m.exists(name)).isInstanceOf(ShuttingDownException.class);
        assertThatThrownBy(() -> m.remove(name)).isInstanceOf(ShuttingDownException.class);
        assertThatThrownBy(() -> m.unremove(name)).isInstanceOf(ShuttingDownException.class);
        assertThatThrownBy(() -> m.list()).isInstanceOf(ShuttingDownException.class);
        assertThatThrownBy(() -> m.listRemoved()).isInstanceOf(ShuttingDownException.class);
    }
}
