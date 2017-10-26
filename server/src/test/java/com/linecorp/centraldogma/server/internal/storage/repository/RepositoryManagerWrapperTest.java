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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

import com.linecorp.centraldogma.server.internal.storage.StorageExistsException;
import com.linecorp.centraldogma.server.internal.storage.StorageNotFoundException;
import com.linecorp.centraldogma.server.internal.storage.project.Project;
import com.linecorp.centraldogma.server.internal.storage.repository.git.GitRepositoryManager;

public class RepositoryManagerWrapperTest {

    @ClassRule
    public static TemporaryFolder rootDir = new TemporaryFolder();

    private static RepositoryManager m;

    @Rule
    public final TestName testName = new TestName();

    @Before
    public void init() throws IOException {
        m = new RepositoryManagerWrapper(new GitRepositoryManager(mock(Project.class), rootDir.getRoot(),
                                                                  ForkJoinPool.commonPool()),
                                         RepositoryWrapper::new);
    }

    @Test
    public void testCreate() {
        String name = testName.getMethodName();
        Repository repo = m.create(name);
        assertTrue(repo instanceof RepositoryWrapper);
        // The cached result will be returned on the second call.
        try {
            m.create(name);
            fail();
        } catch (StorageExistsException ignored) {
            // Expected
        }
    }

    @Test
    public void testGet() {
        String name = testName.getMethodName();
        Repository repo = m.create(name);
        Repository repo2 = m.get(name);

        // Check if the reference is same.
        assertSame(repo, repo2);
    }

    @Test
    public void testRemove() {
        String name = testName.getMethodName();
        m.create(name);
        m.remove(name);
        assertTrue(!m.exists(name));
    }

    @Test
    public void testRemove_failure() {
        String name = testName.getMethodName();
        try {
            m.remove(name);
            fail();
        } catch (StorageNotFoundException ignored) {
            // Expected
        }
    }

    @Test
    public void testUnRemove_failure() {
        String name = testName.getMethodName();
        try {
            m.unremove(name);
            fail();
        } catch (StorageNotFoundException ignored) {
            // Expected
        }
    }

    @Test
    public void testList() {
        final String name = testName.getMethodName();
        final int numNames = 10;
        for (int i = 0; i < numNames; i++) {
            m.create(name + i);
        }
        List<String> names = m.list().entrySet().stream().map(Map.Entry::getKey).collect(Collectors.toList());

        // Check if names is in ascending order
        for (int i = 0; i < numNames - 1; i++) {
            if (names.get(i).compareTo(names.get(i + 1)) > 0) {
                fail("names is not in ascending order");
            }
        }
    }

    @Test
    public void testClose() {
        String name = testName.getMethodName();
        m.create(name);
        assertTrue(m.exists(name));
        m.close();

        try {
            m.get(name);
            fail();
        } catch (IllegalStateException ignored) {
            // Expected
        }

        try {
            m.exists(name);
            fail();
        } catch (IllegalStateException ignored) {
            // Expected
        }

        try {
            m.remove(name);
            fail();
        } catch (IllegalStateException ignored) {
            // Expected
        }

        try {
            m.unremove(name);
            fail();
        } catch (IllegalStateException ignored) {
            // Expected
        }

        try {
            m.list();
            fail();
        } catch (IllegalStateException ignored) {
            // Expected
        }

        try {
            m.listRemoved();
            fail();
        } catch (IllegalStateException ignored) {
            // Expected
        }
    }
}
