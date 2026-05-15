/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.centraldogma.server.internal.replication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.management.ServerStatus;
import com.linecorp.centraldogma.testing.internal.CentralDogmaReplicationExtension;
import com.linecorp.centraldogma.testing.internal.CentralDogmaRuleDelegate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Integration tests for the two replication progress files {@code last_revision} (replay loop
 * progress) and {@code local_last_revision} (highest revision applied via {@code storeLog}).
 */
class ReplicationProgressFilesIntegrationTest {

    private final Map<Integer, SimpleMeterRegistry> meterRegistryMap = new ConcurrentHashMap<>();

    @RegisterExtension
    final CentralDogmaReplicationExtension cluster = new CentralDogmaReplicationExtension(3) {
        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        protected void configureEach(int serverId, CentralDogmaBuilder builder) {
            final SimpleMeterRegistry registry = new SimpleMeterRegistry();
            meterRegistryMap.put(serverId, registry);
            builder.meterRegistry(registry);
        }
    };

    /**
     * A commit on the originator advances {@code last_revision} and {@code local_last_revision}
     * on the originator, and {@code last_revision} (only) on the followers.
     */
    @Test
    void commitAdvancesProgressFiles() throws Exception {
        final CentralDogmaRuleDelegate originator = cluster.servers().get(0);
        final CentralDogmaRuleDelegate follower = cluster.servers().get(1);

        // Snapshot baselines — cluster startup may have already originated some internal commands
        // (e.g., creating the system project), so we compare against pre-commit values rather
        // than assert specific revisions or file (non-)existence.
        final long originatorLastBefore = readLastReplayed(originator);
        final long followerLastBefore = readLastReplayed(follower);
        final long followerLocalLastBefore = readLocalLastAppliedOrMinusOne(follower);

        originator.client().createProject("p_advance").join();

        // last_revision advances on every replica.
        await().untilAsserted(() -> {
            assertThat(readLastReplayed(originator)).isGreaterThan(originatorLastBefore);
            assertThat(readLastReplayed(follower)).isGreaterThan(followerLastBefore);
        });

        // The originator wrote local_last_revision via storeLog → equals last_revision once the
        // childEvent for its own commit has processed.
        await().untilAsserted(() -> {
            assertThat(readLocalLastApplied(originator)).isEqualTo(readLastReplayed(originator));
        });

        // The follower only replayed — its local_last_revision is never written by replayLogs,
        // so it stays at whatever value it had before the originator's commit.
        assertThat(readLocalLastAppliedOrMinusOne(follower)).isEqualTo(followerLocalLastBefore);
    }

    /**
     * The originator's own {@code childEvent} replay must take the skip path; otherwise replay
     * would re-execute the just-applied command and throw {@code RedundantChangeException},
     * flipping the server to read-only mode.
     */
    @Test
    void originatorStaysWritableAfterRepeatedCommits() throws Exception {
        final CentralDogmaRuleDelegate originator = cluster.servers().get(0);
        final BlockingWebClient client = originator.httpClient().blocking();

        for (int i = 0; i < 5; i++) {
            originator.client().createProject("p_repeated_" + i).join();
        }

        // Wait for childEvent replay to run.
        Thread.sleep(500);

        final ServerStatus status = client.prepare()
                                          .get("/api/v1/status")
                                          .asJson(ServerStatus.class)
                                          .execute()
                                          .content();
        assertThat(status.writable()).isTrue();
        assertThat(status.replicating()).isTrue();
    }

    /**
     * After a self-originated commit, the originator's {@code local_last_revision} file content
     * matches its {@code last_revision}, and both equal the result revision of the latest
     * replayed command.
     */
    @Test
    void originatorLocalLastEqualsLastReplayedAfterReplicationSettles() throws Exception {
        final CentralDogmaRuleDelegate originator = cluster.servers().get(0);

        originator.client().createProject("p_eq").join();
        originator.client().createRepository("p_eq", "r_eq").join();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            final long localLast = readLocalLastApplied(originator);
            final long lastReplayed = readLastReplayed(originator);
            assertThat(localLast).isEqualTo(lastReplayed);
            assertThat(localLast).isGreaterThan(0L);
        });
    }

    /**
     * Files survive a full cluster restart and remain at the same values when no commits happen
     * between stop and start.
     */
    @Test
    void filesPersistAcrossClusterRestart() throws Exception {
        final CentralDogmaRuleDelegate originator = cluster.servers().get(0);

        originator.client().createProject("p_persist").join();
        await().untilAsserted(() -> assertThat(readLastReplayed(originator)).isGreaterThan(0L));

        final long originatorLastBefore = readLastReplayed(originator);
        final long originatorLocalLastBefore = readLocalLastApplied(originator);

        cluster.stop();
        cluster.start();

        assertThat(readLastReplayed(originator)).isEqualTo(originatorLastBefore);
        assertThat(readLocalLastApplied(originator)).isEqualTo(originatorLocalLastBefore);

        // New commits should still work after restart.
        originator.client().createProject("p_post_restart").join();
        await().untilAsserted(
                () -> assertThat(readLastReplayed(originator)).isGreaterThan(originatorLastBefore));
    }

    /**
     * A follower that was offline during a commit catches up after individual restart — its
     * {@code last_revision} converges to the originator's.
     */
    @Test
    void followerCatchesUpAfterIndividualRestart() throws Exception {
        final CentralDogmaRuleDelegate originator = cluster.servers().get(0);
        final CentralDogmaRuleDelegate follower = cluster.servers().get(2);

        // Establish baseline replication.
        originator.client().createProject("p_pre_stop").join();
        await().untilAsserted(() -> {
            assertThat(readLastReplayed(follower)).isEqualTo(readLastReplayed(originator));
        });

        // Stop the follower while keeping quorum (2-of-3) alive.
        follower.dogma().stop().join();

        // Originate while the follower is down.
        originator.client().createProject("p_during_stop").join();
        originator.client().createRepository("p_during_stop", "r1").join();

        await().untilAsserted(() -> assertThat(readLastReplayed(originator)).isGreaterThan(0L));
        final long originatorLast = readLastReplayed(originator);

        // Restart the follower.
        follower.dogma().start().join();

        // It catches up via replay.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(readLastReplayed(follower)).isGreaterThanOrEqualTo(originatorLast);
        });
    }

    /**
     * Each replica only writes its own {@code local_last_revision} (in {@code storeLog}), so the
     * file lags behind {@code last_revision} on replicas that have other-originated revisions
     * applied via replay.
     */
    @Test
    void localLastRevisionTracksOnlyOwnSelfOriginations() throws Exception {
        final CentralDogmaRuleDelegate replica0 = cluster.servers().get(0);
        final CentralDogmaRuleDelegate replica1 = cluster.servers().get(1);

        // Step 1: replica0 originates → its file is updated and equals last_revision.
        replica0.client().createProject("p_repl0").join();

        await().untilAsserted(() -> {
            assertThat(readLastReplayed(replica1)).isEqualTo(readLastReplayed(replica0));
            assertThat(readLocalLastApplied(replica0)).isEqualTo(readLastReplayed(replica0));
        });
        final long replica0LocalLastAfterStep1 = readLocalLastApplied(replica0);
        final long replica1LocalLastAfterStep1 = readLocalLastAppliedOrMinusOne(replica1);

        // Step 2: replica1 originates. replica0's local_last_revision stays at its previous
        // value (replica0 only replayed replica1's command, no storeLog ran on it).
        replica1.client().createProject("p_repl1").join();

        await().untilAsserted(() -> {
            assertThat(readLastReplayed(replica0)).isEqualTo(readLastReplayed(replica1));
            assertThat(readLastReplayed(replica0)).isGreaterThan(replica0LocalLastAfterStep1);
        });

        // replica1's local_last_revision advanced past its Step-1 value to its current
        // last_revision.
        assertThat(readLocalLastApplied(replica1)).isEqualTo(readLastReplayed(replica1));
        assertThat(readLocalLastApplied(replica1)).isGreaterThan(replica1LocalLastAfterStep1);
        // replica0's local_last_revision did not advance — its last_revision is now strictly
        // higher than its local_last_revision.
        assertThat(readLocalLastApplied(replica0)).isEqualTo(replica0LocalLastAfterStep1);
        assertThat(readLocalLastApplied(replica0)).isLessThan(readLastReplayed(replica0));
    }

    /**
     * Round-robin commits across all three replicas: after replication settles, every replica's
     * {@code last_revision} converges to the same value, but each replica's
     * {@code local_last_revision} records only its own self-origination history.
     */
    @Test
    void roundRobinCommitsAdvanceEachReplicasOwnLocalLastRevision() throws Exception {
        final CentralDogmaRuleDelegate replica0 = cluster.servers().get(0);
        final CentralDogmaRuleDelegate replica1 = cluster.servers().get(1);
        final CentralDogmaRuleDelegate replica2 = cluster.servers().get(2);

        replica0.client().createProject("p_rr_0").join();
        await().untilAsserted(
                () -> assertThat(readLocalLastApplied(replica0)).isEqualTo(readLastReplayed(replica0)));
        final long replica0LocalLast = readLocalLastApplied(replica0);

        replica1.client().createProject("p_rr_1").join();
        await().untilAsserted(
                () -> assertThat(readLocalLastApplied(replica1)).isEqualTo(readLastReplayed(replica1)));
        final long replica1LocalLast = readLocalLastApplied(replica1);

        replica2.client().createProject("p_rr_2").join();
        await().untilAsserted(
                () -> assertThat(readLocalLastApplied(replica2)).isEqualTo(readLastReplayed(replica2)));
        final long replica2LocalLast = readLocalLastApplied(replica2);

        // last_revision converges across all replicas.
        await().untilAsserted(() -> {
            final long last = readLastReplayed(replica2);
            assertThat(readLastReplayed(replica0)).isEqualTo(last);
            assertThat(readLastReplayed(replica1)).isEqualTo(last);
        });

        // Each replica's local_last_revision is captured at the time of its own commit;
        // replica2's must be the largest because it originated last.
        assertThat(replica0LocalLast).isLessThan(replica1LocalLast);
        assertThat(replica1LocalLast).isLessThan(replica2LocalLast);

        // Older originators' local_last_revision did not advance during subsequent replay of
        // other replicas' commits — so their last_revision is now strictly larger than their
        // local_last_revision.
        assertThat(readLocalLastApplied(replica0)).isEqualTo(replica0LocalLast);
        assertThat(readLocalLastApplied(replica0)).isLessThan(readLastReplayed(replica0));
        assertThat(readLocalLastApplied(replica1)).isEqualTo(replica1LocalLast);
        assertThat(readLocalLastApplied(replica1)).isLessThan(readLastReplayed(replica1));
    }

    /**
     * Each replica's {@code local_last_revision} is a per-replica file: after the cluster is
     * restarted, every replica reads back its OWN value, not another replica's.
     */
    @Test
    void perReplicaLocalLastRevisionPreservedAcrossClusterRestart() throws Exception {
        final CentralDogmaRuleDelegate replica0 = cluster.servers().get(0);
        final CentralDogmaRuleDelegate replica1 = cluster.servers().get(1);
        final CentralDogmaRuleDelegate replica2 = cluster.servers().get(2);

        replica0.client().createProject("p_pre_0").join();
        replica1.client().createProject("p_pre_1").join();
        replica2.client().createProject("p_pre_2").join();

        await().untilAsserted(() -> {
            assertThat(readLastReplayed(replica0)).isEqualTo(readLastReplayed(replica1));
            assertThat(readLastReplayed(replica1)).isEqualTo(readLastReplayed(replica2));
            assertThat(readLocalLastApplied(replica2)).isEqualTo(readLastReplayed(replica2));
        });

        final long replica0LocalLastBefore = readLocalLastApplied(replica0);
        final long replica1LocalLastBefore = readLocalLastApplied(replica1);
        final long replica2LocalLastBefore = readLocalLastApplied(replica2);
        // Sanity: per-replica values must be distinct and ordered (since each replica originated
        // exactly once in order 0 → 1 → 2).
        assertThat(replica0LocalLastBefore).isLessThan(replica1LocalLastBefore);
        assertThat(replica1LocalLastBefore).isLessThan(replica2LocalLastBefore);

        cluster.stop();
        cluster.start();

        // Each replica reads back its own value, not another replica's.
        assertThat(readLocalLastApplied(replica0)).isEqualTo(replica0LocalLastBefore);
        assertThat(readLocalLastApplied(replica1)).isEqualTo(replica1LocalLastBefore);
        assertThat(readLocalLastApplied(replica2)).isEqualTo(replica2LocalLastBefore);
    }

    /**
     * While a follower catches up by replaying commands originated by other replicas, its own
     * {@code local_last_revision} stays unchanged — only {@code last_revision} advances.
     */
    @Test
    void followerLocalLastRevisionUnchangedDuringCatchUp() throws Exception {
        final CentralDogmaRuleDelegate originator = cluster.servers().get(0);
        final CentralDogmaRuleDelegate follower = cluster.servers().get(2);

        // Pre-stop: follower originates once so its local_last_revision file exists with a
        // known value we can compare against later.
        follower.client().createProject("p_follower_pre").join();
        await().untilAsserted(
                () -> assertThat(readLocalLastApplied(follower)).isEqualTo(readLastReplayed(follower)));
        final long followerLocalLastBefore = readLocalLastApplied(follower);

        follower.dogma().stop().join();

        // While follower is down, originator pushes several commits. Replication keeps quorum
        // (2-of-3 still alive).
        originator.client().createProject("p_during_outage_1").join();
        originator.client().createRepository("p_during_outage_1", "r1").join();
        originator.client().createProject("p_during_outage_2").join();

        final long originatorLastAfterCommits = readLastReplayed(originator);
        assertThat(originatorLastAfterCommits).isGreaterThan(followerLocalLastBefore);

        follower.dogma().start().join();

        // Follower catches up — its last_revision converges to the originator's.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(readLastReplayed(follower)).isGreaterThanOrEqualTo(originatorLastAfterCommits);
        });

        // But the follower's local_last_revision did NOT advance: replay does not write
        // local_last_revision, only storeLog does, and the follower did not originate any of
        // the catch-up commands.
        assertThat(readLocalLastApplied(follower)).isEqualTo(followerLocalLastBefore);
        assertThat(readLocalLastApplied(follower)).isLessThan(readLastReplayed(follower));
    }

    /**
     * After a self-originated commit, both gauges on the originator reflect the corresponding
     * file values.
     */
    @Test
    void gaugesReflectFileContentsOnOriginatorAfterCommit() throws Exception {
        final CentralDogmaRuleDelegate originator = cluster.servers().get(0);

        originator.client().createProject("p_gauge").join();

        await().untilAsserted(() -> {
            final long localLast = readLocalLastApplied(originator);
            final long lastReplayed = readLastReplayed(originator);
            assertThat(readLongGauge(1, "replica.last.local.applied.revision")).isEqualTo(localLast);
            assertThat(readLongGauge(1, "replica.last.replayed.revision")).isEqualTo(lastReplayed);
        });
    }

    /**
     * {@code replica.last.replayed.revision} advances on a follower replaying a command from
     * another replica, but {@code replica.last.local.applied.revision} does not — only storeLog
     * advances it.
     */
    @Test
    void gaugesDivergeOnFollowerReplayingOtherReplicaCommit() throws Exception {
        final CentralDogmaRuleDelegate originator = cluster.servers().get(0);
        final CentralDogmaRuleDelegate follower = cluster.servers().get(1);

        final long followerLocalLastBefore =
                readLongGauge(2, "replica.last.local.applied.revision");
        final long followerLastBefore = readLongGauge(2, "replica.last.replayed.revision");

        originator.client().createProject("p_gauge_async").join();

        // last_replayed gauge advances on the follower as it replays.
        await().untilAsserted(() -> {
            assertThat(readLongGauge(2, "replica.last.replayed.revision"))
                    .isGreaterThan(followerLastBefore);
        });

        // But local_applied gauge stays put — the follower never ran storeLog for this command.
        assertThat(readLongGauge(2, "replica.last.local.applied.revision"))
                .isEqualTo(followerLocalLastBefore);
    }

    private long readLongGauge(int serverId, String name) {
        return (long) meterRegistryMap.get(serverId).get(name).gauge().value();
    }

    private static File dataDir(CentralDogmaRuleDelegate dogma) {
        return dogma.dogma().config().dataDir();
    }

    private static long readRev(File file) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            return Long.parseLong(br.readLine().trim());
        }
    }

    private static long readLastReplayed(CentralDogmaRuleDelegate dogma) throws IOException {
        return readRev(new File(dataDir(dogma), "last_revision"));
    }

    private static long readLocalLastApplied(CentralDogmaRuleDelegate dogma) throws IOException {
        return readRev(new File(dataDir(dogma), "local_last_revision"));
    }

    /** Returns the current value, or -1 when the file is absent (replica never originated). */
    private static long readLocalLastAppliedOrMinusOne(CentralDogmaRuleDelegate dogma)
            throws IOException {
        final File file = new File(dataDir(dogma), "local_last_revision");
        return file.exists() ? readRev(file) : -1L;
    }
}
