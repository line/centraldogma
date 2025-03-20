/*
 * Copyright 2025 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.RepositoryMetadata;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.RepositoryManager;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class RemovingMetaRepositoryMetadataServiceTest {

    private static final String TEST_PROJ = "fooProj";
    private static final String TEST_REPO = "repo";

    @RegisterExtension
    static ProjectManagerExtension projectManagerExtension = new ProjectManagerExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        protected void afterExecutorStarted() {
            final ProjectManager projectManager = projectManagerExtension.projectManager();
            final Project project = projectManager.create(TEST_PROJ, Author.SYSTEM);
            final RepositoryManager repoManager = project.repos();
            repoManager.create(TEST_REPO, Author.SYSTEM);

            final MetadataService mds =
                    new MetadataService(projectManager, projectManagerExtension.executor(),
                                        projectManagerExtension.internalProjectInitializer());
            mds.addRepo(Author.SYSTEM, TEST_PROJ, TEST_REPO).join();
            mds.addRepo(Author.SYSTEM, TEST_PROJ, Project.REPO_META).join();
        }
    };

    @Test
    void remove() throws Exception {
        final ProjectManager projectManager = projectManagerExtension.projectManager();
        final Project project = projectManager.get(TEST_PROJ);
        Map<String, RepositoryMetadata> repos = project.metadata().repos();
        assertThat(repos.size()).isEqualTo(2);
        assertThat(repos).containsKey(Project.REPO_META);
        assertThat(repos).containsKey(TEST_REPO);
        System.err.println(project.metadata());
        final RemovingMetaRepositoryMetadataService removingService =
                new RemovingMetaRepositoryMetadataService(projectManager, projectManagerExtension.executor());
        removingService.remove();
        repos = project.metadata().repos();
        assertThat(repos.size()).isOne();
        assertThat(repos).doesNotContainKey(Project.REPO_META);
        assertThat(repos).containsKey(TEST_REPO);
    }
}
