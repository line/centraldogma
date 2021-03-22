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

package com.linecorp.centraldogma.server.internal.storage;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.RepositoryGarbageCollectionConfig;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;

import io.micrometer.core.instrument.Metrics;

class RepositoryGarbageCollectionPluginTest {

    @Test
    void gcWithConfiguration() throws Exception {
        final PluginContext ctx = mock(PluginContext.class);
        final CentralDogmaConfig config = mock(CentralDogmaConfig.class);
        final ProjectManager pm = mock(ProjectManager.class);
        final Project project1 = mock(Project.class);
        final RepositoryManager rm = mock(RepositoryManager.class);
        final Repository repo1 = mock(Repository.class);
        final Repository repo2 = mock(Repository.class);
        final RepositoryGarbageCollectionConfig gcConfig =
                new RepositoryGarbageCollectionConfig(500, "* * * * * ?");

        when(ctx.config()).thenReturn(config);
        when(config.repositoryGarbageCollection()).thenReturn(gcConfig);
        when(ctx.meterRegistry()).thenReturn(Metrics.globalRegistry);

        when(project1.name()).thenReturn("project1");
        when(project1.repos()).thenReturn(rm);

        when(repo2.name()).thenReturn("repo2");

        Revision repo1Rev = new Revision(1);
        Revision repo2Rev = new Revision(1000);
        when(repo1.normalizeNow(Revision.HEAD)).thenReturn(repo1Rev);
        when(repo2.normalizeNow(Revision.HEAD)).thenReturn(repo2Rev);

        when(repo2.gc()).thenReturn(repo2Rev);

        when(ctx.projectManager()).thenReturn(pm);
        when(pm.list()).thenReturn(ImmutableMap.of("foo", project1));
        when(rm.list()).thenReturn(ImmutableMap.of("repo1", repo1, "repo2", repo2));

        final RepositoryGarbageCollectionPlugin gc = new RepositoryGarbageCollectionPlugin();
        gc.initialize(ctx);

        gc.gc(ctx);
        verify(repo1, never()).gc();
        // gc will run only for a large repo.
        verify(repo2).gc();

        // Update the last gc revision
        when(repo2.lastGcRevision()).thenReturn(repo2Rev);

        // Insufficient new commits
        repo1Rev = new Revision(499);
        repo2Rev = new Revision(1499);
        when(repo1.normalizeNow(Revision.HEAD)).thenReturn(repo1Rev);
        when(repo2.normalizeNow(Revision.HEAD)).thenReturn(repo2Rev);

        gc.gc(ctx);

        // The invocation count should not be changed
        verify(repo1, never()).gc();
        verify(repo2).gc();

        // Sufficient pushes
        repo2Rev = new Revision(1500);
        when(repo2.normalizeNow(Revision.HEAD)).thenReturn(repo2Rev);

        gc.gc(ctx);

        verify(repo1, never()).gc();
        verify(repo2, times(2)).gc();
    }
}
