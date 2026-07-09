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
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ReplicationStatus;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RepoStatusManagerTest {

    @RegisterExtension
    static ProjectManagerExtension pmExtension = new ProjectManagerExtension();

    private RepoStatusManager statusManager;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        statusManager = new RepoStatusManager(pmExtension.serverStatusManager(), pmExtension.projectManager(),
                                              meterRegistry);
        statusManager.initialize();
    }

    @Test
    void repoStatus_cruTest() {
        statusManager.updateRepoStatus("test_prj", "test_repo", Author.DEFAULT, ReplicationStatus.READ_ONLY)
                     .join();
        awaitStatus("test_prj", "test_repo", ReplicationStatus.READ_ONLY);

        // Silently ignore the update if the status is the same as the current one.
        statusManager.updateRepoStatus("test_prj", "test_repo", Author.DEFAULT, ReplicationStatus.READ_ONLY)
                     .join();
        assertThat(statusManager.getRepoStatus("test_prj", "test_repo").status())
                .isEqualTo(ReplicationStatus.READ_ONLY);

        statusManager.updateRepoStatus("test_prj", "test_repo", Author.DEFAULT, ReplicationStatus.WRITABLE)
                     .join();
        awaitStatus("test_prj", "test_repo", ReplicationStatus.WRITABLE);
        assertThat(statusManager.getRepoStatus("test_prj", "test_repo")).isEqualTo(
                new RepositoryState("test_prj", "test_repo", ReplicationStatus.WRITABLE, null));
    }

    @Test
    void projectStatus_cruTest() {
        statusManager.updateProjectStatus("test_prj", Author.DEFAULT, ReplicationStatus.READ_ONLY).join();
        awaitStatus("test_prj", "dogma", ReplicationStatus.READ_ONLY);

        // Silently ignore the update if the status is the same as the current one.
        statusManager.updateProjectStatus("test_prj", Author.DEFAULT, ReplicationStatus.READ_ONLY).join();
        assertThat(statusManager.getRepoStatus("test_prj", "dogma").status())
                .isEqualTo(ReplicationStatus.READ_ONLY);

        statusManager.updateProjectStatus("test_prj", Author.DEFAULT, ReplicationStatus.WRITABLE).join();
        awaitStatus("test_prj", "dogma", ReplicationStatus.WRITABLE);
        assertThat(statusManager.getRepoStatus("test_prj", "dogma")).isEqualTo(
                new RepositoryState("test_prj", "dogma", ReplicationStatus.WRITABLE, null));
    }

    @Test
    void readOnlyStatuses_and_metrics() {
        assertThat(statusManager.readOnlyStatuses()).isEmpty();
        assertThat(readOnlyCount()).isZero();

        statusManager.updateRepoStatus("test_prj", "test_repo", Author.DEFAULT, ReplicationStatus.READ_ONLY)
                     .join();
        statusManager.updateProjectStatus("test_prj2", Author.DEFAULT, ReplicationStatus.READ_ONLY).join();

        await().untilAsserted(() -> assertThat(statusManager.readOnlyStatuses())
                .extracting(RepositoryState::projectName, RepositoryState::repoName, RepositoryState::status)
                .containsExactlyInAnyOrder(
                        tuple("test_prj", "test_repo", ReplicationStatus.READ_ONLY),
                        tuple("test_prj2", "dogma", ReplicationStatus.READ_ONLY)));

        assertThat(readOnlyCount()).isEqualTo(2);
        assertThat(scopeGauge("test_prj", "test_repo", "repository")).isOne();
        assertThat(scopeGauge("test_prj2", "dogma", "project")).isOne();

        // Reverting to WRITABLE removes the scope from the list and the metrics.
        statusManager.updateRepoStatus("test_prj", "test_repo", Author.DEFAULT, ReplicationStatus.WRITABLE)
                     .join();
        statusManager.updateProjectStatus("test_prj2", Author.DEFAULT, ReplicationStatus.WRITABLE).join();
        await().untilAsserted(() -> assertThat(statusManager.readOnlyStatuses()).isEmpty());
        assertThat(readOnlyCount()).isZero();
    }

    /**
     * A commit notifies the status listener inline, but {@link RepoStatusManager#initialize()} registers that
     * listener asynchronously on the repository worker. An update committed before the registration lands is
     * only picked up by the listener's initial snapshot, so wait for the cache to catch up.
     */
    private void awaitStatus(String projectName, String repoName, ReplicationStatus expected) {
        await().untilAsserted(() -> assertThat(statusManager.getRepoStatus(projectName, repoName).status())
                .isEqualTo(expected));
    }

    private double readOnlyCount() {
        return meterRegistry.get("repository.read.only.count").gauge().value();
    }

    private double scopeGauge(String projectName, String repoName, String scope) {
        return meterRegistry.get("repository.read.only")
                            .tags("project", projectName, "repo", repoName, "scope", scope)
                            .gauge().value();
    }
}
