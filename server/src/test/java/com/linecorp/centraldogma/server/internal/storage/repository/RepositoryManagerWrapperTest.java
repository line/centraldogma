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

package com.linecorp.centraldogma.server.internal.storage.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.RepositoryExistsException;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.ShuttingDownException;
import com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryManager;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;
import com.linecorp.centraldogma.testing.internal.TemporaryFolderExtension;
import com.linecorp.centraldogma.testing.internal.TestUtil;

class RepositoryManagerWrapperTest {

    private static RepositoryManager m;

    private Executor purgeWorker;

    @RegisterExtension
    static final TemporaryFolderExtension rootDir = new TemporaryFolderExtension();

    @BeforeEach
    void setUp() {
        purgeWorker = mock(Executor.class);
        m = new RepositoryManagerWrapper(new GitRepositoryManager(mock(Project.class),
                                                                  rootDir.getRoot().toFile(),
                                                                  ForkJoinPool.commonPool(),
                                                                  purgeWorker, null),
                                         RepositoryWrapper::new);
    }

    @Test
    void create(TestInfo testInfo) {
        final String name = TestUtil.normalizedDisplayName(testInfo);
        final Repository repo = m.create(name, Author.SYSTEM);
        assertThat(repo).isInstanceOf(RepositoryWrapper.class);
        // The cached result will be returned on the second call.
        assertThatThrownBy(() -> m.create(name, Author.SYSTEM)).isInstanceOf(RepositoryExistsException.class);
    }

    @Test
    void get(TestInfo testInfo) {
        final String name = TestUtil.normalizedDisplayName(testInfo);
        final Repository repo = m.create(name, Author.SYSTEM);
        final Repository repo2 = m.get(name);

        // Check if the reference is same.
        assertThat(repo).isSameAs(repo2);
    }

    @Test
    void remove(TestInfo testInfo) {
        final String name = TestUtil.normalizedDisplayName(testInfo);
        m.create(name, Author.SYSTEM);
        m.remove(name);
        assertThat(m.exists(name)).isFalse();
    }

    @Test
    void remove_failure(TestInfo testInfo) {
        final String name = TestUtil.normalizedDisplayName(testInfo);
        assertThatThrownBy(() -> m.remove(name)).isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    void unremove_failure(TestInfo testInfo) {
        final String name = TestUtil.normalizedDisplayName(testInfo);
        assertThatThrownBy(() -> m.unremove(name)).isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    void list(TestInfo testInfo) {
        final String name = TestUtil.normalizedDisplayName(testInfo);
        final int numNames = 10;
        for (int i = 0; i < numNames; i++) {
            m.create(name + i, Author.SYSTEM);
        }
        final List<String> names = new ArrayList<>(m.list().keySet());

        // Check if names is in ascending order
        for (int i = 0; i < numNames - 1; i++) {
            if (names.get(i).compareTo(names.get(i + 1)) > 0) {
                fail("names is not in ascending order");
            }
        }
    }

    @Test
    void markForPurge(TestInfo testInfo) {
        final String name = TestUtil.normalizedDisplayName(testInfo);
        m.create(name, Author.SYSTEM);
        m.remove(name);
        m.markForPurge(name);
        verify(purgeWorker).execute(any());
        assertThat(m.listRemoved().keySet()).doesNotContain(name);
    }

    @Test
    void purgeMarked(TestInfo testInfo) {
        final String name = TestUtil.normalizedDisplayName(testInfo);
        final int numNames = 10;
        for (int i = 0; i < numNames; i++) {
            final String targetName = name + i;
            m.create(targetName, Author.SYSTEM);
            m.remove(targetName);
            m.markForPurge(targetName);
        }
        m.purgeMarked();
        for (int i = 0; i < numNames; i++) {
            final String targetName = name + i;
            assertThatThrownBy(() -> m.get(targetName)).isInstanceOf(RepositoryNotFoundException.class);
        }
    }

    @Test
    void close(TestInfo testInfo) {
        final String name = TestUtil.normalizedDisplayName(testInfo);
        m.create(name, Author.SYSTEM);
        assertThat(m.exists(name)).isTrue();
        m.close(ShuttingDownException::new);

        assertThatThrownBy(() -> m.get(name)).isInstanceOf(ShuttingDownException.class);
        assertThatThrownBy(() -> m.exists(name)).isInstanceOf(ShuttingDownException.class);
        assertThatThrownBy(() -> m.remove(name)).isInstanceOf(ShuttingDownException.class);
        assertThatThrownBy(() -> m.unremove(name)).isInstanceOf(ShuttingDownException.class);
        assertThatThrownBy(() -> m.list()).isInstanceOf(ShuttingDownException.class);
        assertThatThrownBy(() -> m.listRemoved()).isInstanceOf(ShuttingDownException.class);
    }
}
