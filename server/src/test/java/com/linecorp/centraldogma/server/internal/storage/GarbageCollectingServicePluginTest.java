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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;

class GarbageCollectingServicePluginTest {

    @Test
    void gcAllRepository() throws Exception {
        final PluginContext ctx = mock(PluginContext.class);
        final ProjectManager pm = mock(ProjectManager.class);
        final Project project = mock(Project.class);
        final RepositoryManager rm = mock(RepositoryManager.class);
        final Repository repo1 = mock(Repository.class);
        final Repository repo2 = mock(Repository.class);

        when(ctx.projectManager())
                .thenReturn(pm);
        when(pm.list()).thenReturn(ImmutableMap.of("foo", project));
        when(project.repos()).thenReturn(rm);
        when(rm.list()).thenReturn(ImmutableMap.of("repo1", repo1, "repo2", repo2));
        GarbageCollectingServicePlugin.gc(ctx);
        verify(repo1).gc();
        verify(repo2).gc();
    }
}
