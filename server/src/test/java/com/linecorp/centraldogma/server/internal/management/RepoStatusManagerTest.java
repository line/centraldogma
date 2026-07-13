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

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.ReplicationStatus;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.testing.internal.ProjectManagerExtension;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
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
        final ProjectManager pm = pmExtension.projectManager();
        pm.create("test_prj", Author.SYSTEM);
        pm.get("test_prj").repos().create("test_repo", Author.SYSTEM);
        pm.create("test_prj2", Author.SYSTEM);
        try {
            assertThat(statusManager.readOnlyStatuses()).isEmpty();
            assertThat(readOnlyCount()).isZero();

            statusManager.updateRepoStatus("test_prj", "test_repo", Author.DEFAULT,
                                           ReplicationStatus.READ_ONLY).join();
            statusManager.updateProjectStatus("test_prj2", Author.DEFAULT, ReplicationStatus.READ_ONLY)
                         .join();

            await().untilAsserted(() -> assertThat(statusManager.readOnlyStatuses())
                    .extracting(RepositoryState::projectName, RepositoryState::repoName,
                                RepositoryState::status)
                    .containsExactlyInAnyOrder(
                            tuple("test_prj", "test_repo", ReplicationStatus.READ_ONLY),
                            tuple("test_prj2", "dogma", ReplicationStatus.READ_ONLY)));

            assertThat(readOnlyCount()).isEqualTo(2);
            // A project-scoped entry is the one whose repository is "dogma".
            assertThat(readOnlyGauge("test_prj", "test_repo")).isOne();
            assertThat(readOnlyGauge("test_prj2", "dogma")).isOne();

            // Reverting to WRITABLE removes the entry from the list and the metrics.
            statusManager.updateRepoStatus("test_prj", "test_repo", Author.DEFAULT,
                                           ReplicationStatus.WRITABLE).join();
            statusManager.updateProjectStatus("test_prj2", Author.DEFAULT, ReplicationStatus.WRITABLE).join();
            await().untilAsserted(() -> assertThat(statusManager.readOnlyStatuses()).isEmpty());
            assertThat(readOnlyCount()).isZero();
        } finally {
            statusManager.removeRepoStatus("test_prj", "test_repo", Author.DEFAULT).join();
            statusManager.removeProjectStatus("test_prj2", Author.DEFAULT).join();
        }
    }

    @Test
    void softDeletedRepositoryIsHiddenAndRestored() {
        final ProjectManager pm = pmExtension.projectManager();
        pm.create("readonly_soft", Author.SYSTEM);
        pm.get("readonly_soft").repos().create("repo", Author.SYSTEM);
        try {
            statusManager.updateRepoStatus("readonly_soft", "repo", Author.DEFAULT,
                                           ReplicationStatus.READ_ONLY).join();
            await().untilAsserted(() -> assertThat(statusManager.readOnlyStatuses())
                    .extracting(RepositoryState::projectName, RepositoryState::repoName)
                    .containsExactly(tuple("readonly_soft", "repo")));
            assertThat(readOnlyCount()).isOne();
            assertThat(readOnlyGauge("readonly_soft", "repo")).isOne();

            // Soft-removing the repository hides it from the list and metrics but keeps the status file.
            pm.get("readonly_soft").repos().remove("repo");
            statusManager.refreshReadOnlyMetrics();
            assertThat(statusManager.readOnlyStatuses()).isEmpty();
            assertThat(readOnlyCount()).isZero();
            assertThat(readOnlyGaugeOrNull("readonly_soft", "repo")).isNull();
            // The status is preserved, so getRepoStatus() still reports READ_ONLY.
            assertThat(statusManager.getRepoStatus("readonly_soft", "repo").status())
                    .isEqualTo(ReplicationStatus.READ_ONLY);

            // Restoring the repository brings the read-only status back.
            pm.get("readonly_soft").repos().unremove("repo");
            statusManager.refreshReadOnlyMetrics();
            assertThat(statusManager.readOnlyStatuses())
                    .extracting(RepositoryState::projectName, RepositoryState::repoName)
                    .containsExactly(tuple("readonly_soft", "repo"));
            assertThat(readOnlyCount()).isOne();
            assertThat(readOnlyGauge("readonly_soft", "repo")).isOne();
        } finally {
            // Clean up the shared state so other tests start from an empty read-only list.
            statusManager.removeRepoStatus("readonly_soft", "repo", Author.DEFAULT).join();
        }
    }

    @Test
    void softDeletedProjectIsHiddenAndRestored() {
        final ProjectManager pm = pmExtension.projectManager();
        pm.create("readonly_prj_soft", Author.SYSTEM);
        pm.get("readonly_prj_soft").repos().create("repo", Author.SYSTEM);
        try {
            statusManager.updateRepoStatus("readonly_prj_soft", "repo", Author.DEFAULT,
                                           ReplicationStatus.READ_ONLY).join();
            statusManager.updateProjectStatus("readonly_prj_soft", Author.DEFAULT,
                                              ReplicationStatus.READ_ONLY).join();
            await().untilAsserted(() -> assertThat(statusManager.readOnlyStatuses())
                    .extracting(RepositoryState::projectName, RepositoryState::repoName)
                    .containsExactlyInAnyOrder(tuple("readonly_prj_soft", "repo"),
                                               tuple("readonly_prj_soft", "dogma")));
            assertThat(readOnlyCount()).isEqualTo(2);

            // Soft-removing the whole project hides both the repo-scope and project-scope entries.
            pm.remove("readonly_prj_soft");
            statusManager.refreshReadOnlyMetrics();
            assertThat(statusManager.readOnlyStatuses()).isEmpty();
            assertThat(readOnlyCount()).isZero();
            assertThat(readOnlyGaugeOrNull("readonly_prj_soft", "repo")).isNull();
            assertThat(readOnlyGaugeOrNull("readonly_prj_soft", "dogma")).isNull();

            // Restoring the project brings both entries back.
            pm.unremove("readonly_prj_soft");
            statusManager.refreshReadOnlyMetrics();
            assertThat(statusManager.readOnlyStatuses())
                    .extracting(RepositoryState::projectName, RepositoryState::repoName)
                    .containsExactlyInAnyOrder(tuple("readonly_prj_soft", "repo"),
                                               tuple("readonly_prj_soft", "dogma"));
            assertThat(readOnlyCount()).isEqualTo(2);
        } finally {
            statusManager.removeProjectStatus("readonly_prj_soft", Author.DEFAULT).join();
        }
    }

    @Test
    void purgingRepositoryRemovesStatusAndMetrics() {
        final ProjectManager pm = pmExtension.projectManager();
        pm.create("readonly_purge", Author.SYSTEM);
        pm.get("readonly_purge").repos().create("repo", Author.SYSTEM);
        try {
            statusManager.updateRepoStatus("readonly_purge", "repo", Author.DEFAULT,
                                           ReplicationStatus.READ_ONLY).join();
            await().untilAsserted(() -> assertThat(statusManager.readOnlyStatuses()).hasSize(1));
            assertThat(readOnlyCount()).isOne();

            // Purging deletes the status file and evicts the cache entry and metrics directly.
            statusManager.removeRepoStatus("readonly_purge", "repo", Author.DEFAULT).join();
            assertThat(statusManager.readOnlyStatuses()).isEmpty();
            assertThat(readOnlyCount()).isZero();
            assertThat(readOnlyGaugeOrNull("readonly_purge", "repo")).isNull();
            // The status file is gone, so the repository is writable again even though it still exists.
            assertThat(statusManager.getRepoStatus("readonly_purge", "repo").status())
                    .isEqualTo(ReplicationStatus.WRITABLE);
        } finally {
            statusManager.removeRepoStatus("readonly_purge", "repo", Author.DEFAULT).join();
        }
    }

    @Test
    void purgingProjectRemovesAllStatuses() {
        final ProjectManager pm = pmExtension.projectManager();
        pm.create("readonly_proj", Author.SYSTEM);
        pm.get("readonly_proj").repos().create("repo", Author.SYSTEM);
        try {
            statusManager.updateRepoStatus("readonly_proj", "repo", Author.DEFAULT,
                                           ReplicationStatus.READ_ONLY).join();
            statusManager.updateProjectStatus("readonly_proj", Author.DEFAULT,
                                              ReplicationStatus.READ_ONLY).join();
            await().untilAsserted(() -> assertThat(statusManager.readOnlyStatuses()).hasSize(2));

            statusManager.removeProjectStatus("readonly_proj", Author.DEFAULT).join();
            assertThat(statusManager.readOnlyStatuses()).isEmpty();
            assertThat(readOnlyCount()).isZero();
        } finally {
            statusManager.removeProjectStatus("readonly_proj", Author.DEFAULT).join();
        }
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

    /**
     * The repository name tells the scope apart, so the gauge carries no other tag.
     */
    private double readOnlyGauge(String projectName, String repoName) {
        final Gauge gauge = meterRegistry.get("repository.read.only")
                                         .tags("project", projectName, "repo", repoName)
                                         .gauge();
        assertThat(gauge.getId().getTags())
                .containsExactlyInAnyOrder(Tag.of("project", projectName), Tag.of("repo", repoName));
        return gauge.value();
    }

    @Nullable
    private Gauge readOnlyGaugeOrNull(String projectName, String repoName) {
        return meterRegistry.find("repository.read.only")
                            .tags("project", projectName, "repo", repoName)
                            .gauge();
    }
}
