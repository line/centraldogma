/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.centraldogma.server.internal.management;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ReplicationStatus;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

class RepoStatusManagerTest {

    @RegisterExtension
    static ProjectManagerExtension pmExtension = new ProjectManagerExtension();

    private RepoStatusManager statusManager;

    @BeforeEach
    void setUp() {
        statusManager = new RepoStatusManager(pmExtension.serverStatusManager(), pmExtension.projectManager());
        statusManager.initialize();
    }

    @Test
    void repoStatus_cruTest() {
        statusManager.updateRepoStatus("test_prj", "test_repo", Author.DEFAULT, ReplicationStatus.READ_ONLY)
                     .join();
        RepositoryState entity = statusManager.getRepoStatus("test_prj", "test_repo");
        assertThat(entity.status()).isEqualTo(ReplicationStatus.READ_ONLY);

        // Silently ignore the update if the status is the same as the current one.
        statusManager.updateRepoStatus("test_prj", "test_repo", Author.DEFAULT, ReplicationStatus.READ_ONLY)
                     .join();
        entity = statusManager.getRepoStatus("test_prj", "test_repo");
        assertThat(entity.status()).isEqualTo(ReplicationStatus.READ_ONLY);

        statusManager.updateRepoStatus("test_prj", "test_repo", Author.DEFAULT, ReplicationStatus.WRITABLE)
                     .join();
        entity = statusManager.getRepoStatus("test_prj", "test_repo");
        assertThat(entity.status()).isEqualTo(ReplicationStatus.WRITABLE);
    }

    @Test
    void projectStatus_cruTest() {
        statusManager.updateProjectStatus("test_prj", Author.DEFAULT, ReplicationStatus.READ_ONLY).join();
        RepositoryState entity = statusManager.getRepoStatus("test_prj", "dogma");
        assertThat(entity.status()).isEqualTo(ReplicationStatus.READ_ONLY);

        // Silently ignore the update if the status is the same as the current one.
        statusManager.updateProjectStatus("test_prj", Author.DEFAULT, ReplicationStatus.READ_ONLY).join();
        entity = statusManager.getRepoStatus("test_prj", "dogma");
        assertThat(entity.status()).isEqualTo(ReplicationStatus.READ_ONLY);

        statusManager.updateProjectStatus("test_prj", Author.DEFAULT, ReplicationStatus.WRITABLE).join();
        entity = statusManager.getRepoStatus("test_prj", "dogma");
        assertThat(entity.status()).isEqualTo(ReplicationStatus.WRITABLE);
    }
}
