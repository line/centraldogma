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
 * under the License
 */

package com.linecorp.centraldogma.server.internal.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.internal.storage.project.DefaultProjectManager;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;

import io.micrometer.core.instrument.MeterRegistry;

class DefaultProjectManagerTest {

    @TempDir
    File tempDir;

    @Test
    void testProjectPurgeMarked() {
        final AtomicInteger counter = new AtomicInteger();
        final DefaultProjectManager pm = new DefaultProjectManager(
                tempDir,
                MoreExecutors.directExecutor(),
                (Runnable r) -> counter.incrementAndGet(),
                mock(MeterRegistry.class),
                null);

        final String projectName = "foo";
        final String repoName = "bar";
        final Project project = pm.create(projectName, Author.SYSTEM);
        final RepositoryManager repos = project.repos();
        repos.create(repoName, Author.SYSTEM);
        repos.remove(repoName);
        repos.markForPurge(repoName);
        repos.purgeMarked();
        assertThat(counter.get()).isEqualTo(1);

        pm.remove(projectName);
        pm.markForPurge(projectName);
        pm.purgeMarked();
        assertThat(counter.get()).isEqualTo(2);
    }
}
