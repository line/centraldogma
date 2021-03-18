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
package com.linecorp.centraldogma.server.internal.replication;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreV2;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.QuotaConfig;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommitResult;
import com.linecorp.centraldogma.server.command.NormalizingPushCommand;
import com.linecorp.centraldogma.server.command.PushAsIsCommand;

class ZooKeeperCommandExecutorTest {

    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperCommandExecutorTest.class);

    private static final Change<String> pushChange = Change.ofTextUpsert("/foo", "bar");
    private static final Change<String> normalizedChange = Change.ofTextUpsert("/foo", "bar_normalized");

    @Test
    void testLogWatch() throws Exception {
        // The 5th replica is used for ensuring the quorum.
        try (Cluster cluster = Cluster.of(ZooKeeperCommandExecutorTest::newMockDelegate)) {

            final Replica replica1 = cluster.get(0);
            final Replica replica2 = cluster.get(1);
            final Replica replica3 = cluster.get(2);
            final Replica replica4 = cluster.get(3);
            replica4.commandExecutor().stop().join();

            final Command<Void> command1 = Command.createRepository(Author.SYSTEM, "project", "repo1");
            replica1.commandExecutor().execute(command1).join();

            final Optional<ReplicationLog<?>> commandResult2 = replica1.commandExecutor().loadLog(0, false);
            assertThat(commandResult2).isPresent();
            assertThat(commandResult2.get().command()).isEqualTo(command1);
            assertThat(commandResult2.get().result()).isNull();

            await().untilAsserted(() -> verify(replica1.delegate()).apply(eq(command1)));
            await().untilAsserted(() -> verify(replica2.delegate()).apply(eq(command1)));
            await().untilAsserted(() -> verify(replica3.delegate()).apply(eq(command1)));

            await().until(replica1::existsLocalRevision);
            await().until(replica2::existsLocalRevision);
            await().until(replica3::existsLocalRevision);

            assertThat(replica1.localRevision()).isEqualTo(0L);
            assertThat(replica2.localRevision()).isEqualTo(0L);
            assertThat(replica3.localRevision()).isEqualTo(0L);

            // Stop the 3rd replica and check if the 1st and 2nd replicas still replay the logs.
            replica3.commandExecutor().stop().join();

            final Command<?> command2 = Command.createProject(Author.SYSTEM, "foo");
            replica1.commandExecutor().execute(command2).join();
            await().untilAsserted(() -> verify(replica1.delegate()).apply(eq(command2)));
            await().untilAsserted(() -> verify(replica2.delegate()).apply(eq(command2)));
            await().untilAsserted(() -> verify(replica3.delegate(), times(0)).apply(eq(command2)));

            // Start the 3rd replica back again and check if it catches up.
            replica3.commandExecutor().start().join();
            verifyTwoIndependentCommands(replica3, command1, command2);

            // Start the 4th replica and check if it catches up even if it started from scratch.
            replica4.commandExecutor().start().join();
            verifyTwoIndependentCommands(replica4, command1, command2);
        }
    }

    /**
     * Verifies that the specified {@link Replica} received the specified two commands, regardless of their
     * order.
     */
    private static void verifyTwoIndependentCommands(Replica replica,
                                                     Command<?> command1,
                                                     Command<?> command2) {
        final AtomicReference<Command<?>> lastCommand = new AtomicReference<>();
        verify(replica.delegate(), timeout(TimeUnit.SECONDS.toMillis(2)).times(1)).apply(argThat(c -> {
            if (lastCommand.get() != null) {
                return c.equals(lastCommand.get());
            }

            if (c.equals(command1) || c.equals(command2)) {
                lastCommand.set(c);
                return true;
            }

            return false;
        }));

        final Command<?> expected = lastCommand.get().equals(command1) ? command2 : command1;
        verify(replica.delegate(), timeout(TimeUnit.SECONDS.toMillis(2)).times(1)).apply(argThat(c -> {
            return c.equals(expected);
        }));
    }

    /**
     * Tests if making commits simultaneously from multiple replicas does not make them go out of sync.
     *
     * <p>To test this, each replica keeps its own atomic integer counter that counts the number of commands
     * it executed. If N commits were made across the cluster, each replica's counter should be N if
     * replication worked as expected.
     *
     * <p>Additionally, to ensure the ordering of commits, on each commit executed, a replica will return
     * the counter value as the revision number of the commit.
     *
     * <p>If any of the commits executed previously from other replicas are not replayed before executing
     * a new commit, the revision number of the new commit will be smaller than expected.
     *
     * <p>For example, let's assume there are two replicas 'A' and 'B' and the replica A creates a new commit.
     * The new commit gets the revision number '1'.
     *
     * <p>If the replica B does not replay the commit from the replica A before creating a new commit,
     * the replica B's new commit will get the revision number '1', which means both replica A and B have two
     * different commits with the same revision.
     *
     * <p>If the replica B replays the commit from the replica A before creating a new commit, the replica B's
     * new commit will get the revision number '2', as expected.
     *
     * <p>As a result, all replicas will contain the same number of commits and their revision numbers must
     * increase by 1 from 1.
     */
    @Test
    void testRace() throws Exception {
        // Each replica has its own AtomicInteger which counts the number of commands
        // it executed/replayed so far.

        try (Cluster cluster = Cluster.of(() -> {
            final AtomicInteger counter = new AtomicInteger();
            return command -> completedFuture(new Revision(counter.incrementAndGet()));
        })) {
            final Command<CommitResult> command = Command.push(null, Author.SYSTEM, "foo", "bar",
                                                               new Revision(42), "", "", Markup.PLAINTEXT,
                                                               ImmutableList.of());
            assert command instanceof NormalizingPushCommand;
            final PushAsIsCommand asIsCommand = ((NormalizingPushCommand) command).asIs(
                    CommitResult.of(new Revision(43), ImmutableList.of()));

            final int COMMANDS_PER_REPLICA = 7;
            final List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (final Replica r : cluster) {
                futures.add(CompletableFuture.runAsync(() -> {
                    for (int j = 0; j < COMMANDS_PER_REPLICA; j++) {
                        try {
                            r.commandExecutor().execute(asIsCommand).join();
                        } catch (Exception e) {
                            throw new Error(e);
                        }
                    }
                }));
            }

            for (CompletableFuture<Void> f : futures) {
                f.get();
            }

            for (Replica r : cluster) {
                for (int i = 0; i < COMMANDS_PER_REPLICA * cluster.size(); i++) {
                    @SuppressWarnings("unchecked")
                    final ReplicationLog<Revision> log =
                            (ReplicationLog<Revision>) r.commandExecutor().loadLog(i, false).get();

                    assertThat(log.result().major()).isEqualTo(i + 1);
                }
            }
        }
    }

    /**
     * Makes sure that we can stop a replica that's waiting for the initial quorum.
     */
    @Test
    void stopWhileWaitingForInitialQuorum() throws Exception {
        try (Cluster cluster = Cluster.builder()
                                      .numReplicas(2)
                                      .autoStart(false)
                                      .build(ZooKeeperCommandExecutorTest::newMockDelegate)) {
            final CompletableFuture<Void> startFuture = cluster.get(0).commandExecutor().start();
            cluster.get(0).commandExecutor().stop().join();

            assertThat(startFuture).hasFailedWithThrowableThat()
                                   .isInstanceOf(InterruptedException.class)
                                   .hasMessageContaining("before joining");
        }
    }

    @Test
    void hierarchicalQuorums() throws Throwable {
        try (Cluster cluster = Cluster.builder()
                                      .numReplicas(9)
                                      .numGroup(3)
                                      .build(ZooKeeperCommandExecutorTest::newMockDelegate)) {

            for (int i = 0; i < cluster.size(); i++) {
                final Map<String, Double> meters = MoreMeters.measureAll(cluster.get(i).meterRegistry());
                assertThat(meters).containsEntry("replica.groupId#value", (i / 3) + 1.0);
            }
            final Replica replica1 = cluster.get(0);

            final Command<Void> command1 = Command.createRepository(Author.SYSTEM, "project", "repo1");
            replica1.commandExecutor().execute(command1).join();

            final Optional<ReplicationLog<?>> commandResult2 = replica1.commandExecutor().loadLog(0, false);
            assertThat(commandResult2).isPresent();
            assertThat(commandResult2.get().command()).isEqualTo(command1);
            assertThat(commandResult2.get().result()).isNull();

            withReplica(cluster, replica -> {
                await().untilAsserted(() -> verify(replica.delegate()).apply(eq(command1)));
            });

            withReplica(cluster, replica -> await().until(replica::existsLocalRevision));
            withReplica(cluster, replica -> assertThat(replica.localRevision()).isEqualTo(0L));
        }
    }

    @Test
    @Timeout(120)
    void hierarchicalQuorumsWithFailOver() throws Throwable {
        try (Cluster cluster = Cluster.builder()
                                      .numReplicas(9)
                                      .numGroup(3)
                                      .build(ZooKeeperCommandExecutorTest::newMockDelegate)) {

            final Replica replica1 = cluster.get(0);
            // Stop Group 3, but we can have a majority of votes from Group 1 and Group 2.
            for (int i = 0; i < cluster.size(); i++) {
                final Replica replica = cluster.get(i);
                if (i / 3 == 2) {
                    replica.commandExecutor().stop().join();
                }
            }

            final Command<Void> command1 = Command.createRepository(Author.SYSTEM, "project", "repo1");
            replica1.commandExecutor().execute(command1).join();

            final ReplicationLog<?> commandResult1 = replica1.commandExecutor().loadLog(0, false).get();
            assertThat(commandResult1.command()).isEqualTo(command1);
            assertThat(commandResult1.result()).isNull();

            for (int i = 0; i < cluster.size(); i++) {
                final Replica replica = cluster.get(i);
                if (i / 3 != 2) {
                    await().untilAsserted(() -> verify(replica.delegate()).apply(eq(command1)));
                }
            }

            // Stop one instance each in Group 1 and Group 2. Normal quorums need 5 instances for a majority of
            // votes if the number of participant is 9.
            // However, hierarchical quorums only need a 4 instances for a majority of votes.
            cluster.get(1).commandExecutor().stop().join();
            cluster.get(4).commandExecutor().stop().join();

            final Command<CommitResult> normalizingPushCommand =
                    Command.push(0L, Author.SYSTEM, "project", "repo1", new Revision(1),
                                 "summary", "detail",
                                 Markup.PLAINTEXT,
                                 ImmutableList.of(pushChange));

            assert normalizingPushCommand instanceof NormalizingPushCommand;
            final PushAsIsCommand asIsCommand = ((NormalizingPushCommand) normalizingPushCommand).asIs(
                    CommitResult.of(new Revision(2), ImmutableList.of(normalizedChange)));

            assertThat(replica1.commandExecutor().execute(normalizingPushCommand).join().revision())
                    .isEqualTo(new Revision(2));
            final ReplicationLog<?> commandResult2 = replica1.commandExecutor().loadLog(1, false).get();
            assertThat(commandResult2.command()).isEqualTo(asIsCommand);
            assertThat(commandResult2.result()).isInstanceOf(Revision.class);

            // pushAsIs is applied for other replicas.
            await().untilAsserted(() -> verify(cluster.get(2).delegate()).apply(eq(asIsCommand)));
            await().untilAsserted(() -> verify(cluster.get(3).delegate()).apply(eq(asIsCommand)));
            await().untilAsserted(() -> verify(cluster.get(5).delegate()).apply(eq(asIsCommand)));

            // Stop one instance in Group 1. The hierarchical quorums is not working anymore.
            cluster.get(2).commandExecutor().stop().join();

            final Command<Void> command3 = Command.createRepository(Author.SYSTEM, "project", "repo3");
            assertThatThrownBy(() -> replica1.commandExecutor().execute(command3).get(10, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);

            // Restart two instances in Group 3, so the hierarchical quorums should be working again.
            final CompletableFuture<Void> replica7Start = cluster.get(7).commandExecutor().start();
            final CompletableFuture<Void> replica8Start = cluster.get(8).commandExecutor().start();
            replica7Start.join();
            replica8Start.join();

            // The command executed while the Group 3 was down should be relayed.
            await().untilAsserted(() -> verify(cluster.get(7).delegate()).apply(eq(asIsCommand)));
            await().untilAsserted(() -> verify(cluster.get(8).delegate()).apply(eq(asIsCommand)));

            await().untilAsserted(() -> verify(cluster.get(0).delegate()).apply(eq(command3)));
            await().untilAsserted(() -> verify(cluster.get(3).delegate()).apply(eq(command3)));
            await().untilAsserted(() -> verify(cluster.get(5).delegate()).apply(eq(command3)));
            await().untilAsserted(() -> verify(cluster.get(7).delegate()).apply(eq(command3)));
            await().untilAsserted(() -> verify(cluster.get(8).delegate()).apply(eq(command3)));
        }
    }

    @Test
    void hierarchicalQuorums_writingOnZeroWeightReplica() throws Throwable {
        try (Cluster cluster = Cluster.builder()
                                      .numReplicas(9)
                                      .numGroup(3)
                                      .weightMappingFunction((groupId, serverId) -> serverId == 1 ? 0 : 1)
                                      .build(ZooKeeperCommandExecutorTest::newMockDelegate)) {

            // The replica1, which has zero-weight, should be excluded from the hierarchical quorums.
            // However the communication with ZooKeeper cluster should work correctly.
            final Replica replica1 = cluster.get(0);

            final Command<Void> command1 = Command.createRepository(Author.SYSTEM, "project", "repo1");
            replica1.commandExecutor().execute(command1).join();

            final ReplicationLog<?> commandResult1 = replica1.commandExecutor().loadLog(0, false).get();
            assertThat(commandResult1.command()).isEqualTo(command1);
            assertThat(commandResult1.result()).isNull();

            for (Replica replica : cluster) {
                await().untilAsserted(() -> verify(replica.delegate()).apply(eq(command1)));
            }
        }
    }

    @Test
    void hierarchicalQuorums_replayingOnZeroWeightReplica() throws Throwable {
        try (Cluster cluster = Cluster.builder()
                                      .numReplicas(9)
                                      .numGroup(3)
                                      .weightMappingFunction((groupId, serverId) -> serverId == 2 ? 0 : 1)
                                      .build(ZooKeeperCommandExecutorTest::newMockDelegate)) {

            final Replica replica1 = cluster.get(0);
            final Command<Void> command1 = Command.createRepository(Author.SYSTEM, "project", "repo1");
            replica1.commandExecutor().execute(command1).join();

            final ReplicationLog<?> commandResult1 = replica1.commandExecutor().loadLog(0, false).get();
            assertThat(commandResult1.command()).isEqualTo(command1);
            assertThat(commandResult1.result()).isNull();

            // The ReplicationLog should be relayed to replica2 which has zero-weight.
            for (Replica replica : cluster) {
                await().untilAsserted(() -> verify(replica.delegate()).apply(eq(command1)));
            }
        }
    }

    private static void withReplica(Cluster cluster, ThrowingConsumer<Replica> consumer)
            throws Throwable {
        for (Replica replica : cluster) {
            consumer.accept(replica);
        }
    }

    @Test
    void lockTimeout() throws Exception {
        final AtomicBoolean neverEnding = new AtomicBoolean();
        final AtomicReference<CompletableFuture<?>> pendingFuture = new AtomicReference<>();
        final Supplier<Function<Command<?>, CompletableFuture<?>>> mockDelegate = () -> command -> {
            if (neverEnding.get()) {
                final CompletableFuture<Object> future = new CompletableFuture<>();
                pendingFuture.set(future);
                return future;
            } else {
                return newMockDelegate().apply(command);
            }
        };


        try (Cluster cluster = Cluster.builder()
                                      .numReplicas(1)
                                      .build(mockDelegate)) {

            final Replica replica = cluster.get(0);
            final ZooKeeperCommandExecutor executor = replica.commandExecutor();

            neverEnding.set(true);
            final Command<Void> command = Command.createRepository(Author.SYSTEM, "project", "repo1");
            executor.execute(command);
            // Await until the first command is executed.
            await().untilAtomic(pendingFuture, Matchers.notNullValue());

            final CompletableFuture<Void> result = executor.execute(command);
            await().between(Duration.ofSeconds(9), Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(result.isCompletedExceptionally()).isTrue();
                final Throwable cause = catchThrowable(result::join);
                assertThat(cause).isInstanceOf(CompletionException.class);
                assertThat(cause.getCause()).isInstanceOf(ReplicationException.class)
                        .hasMessageContaining("Failed to acquire a lock for /project in 10 seconds");
            });
            pendingFuture.get().complete(null);
        }
    }

    @Test
    void metrics() throws Exception {
        try (Cluster cluster = Cluster.builder()
                                      .numReplicas(1)
                                      .build(ZooKeeperCommandExecutorTest::newMockDelegate)) {

            final Map<String, Double> meters = MoreMeters.measureAll(cluster.get(0).meterRegistry());
            meters.forEach((k, v) -> logger.debug("{}={}", k, v));
            assertThat(meters).containsKeys("executor#total{name=zkCommandExecutor}",
                                            "executor#total{name=zkLeaderSelector}",
                                            "executor#total{name=zkLogWatcher}",
                                            "executor#total{name=quotaExecutor}",
                                            "executor.pool.size#value{name=zkCommandExecutor}",
                                            "executor.pool.size#value{name=zkLeaderSelector}",
                                            "executor.pool.size#value{name=zkLogWatcher}",
                                            "executor.pool.size#value{name=quotaExecutor}",
                                            "replica.has.leadership#value",
                                            "replica.id#value",
                                            "replica.last.replayed.revision#value",
                                            "replica.read.only#value",
                                            "replica.replicating#value",
                                            "replica.zk.alive.client.connections#value",
                                            "replica.zk.approximate.data.size#value",
                                            "replica.zk.data.dir.size#value",
                                            "replica.zk.ephemerals#value",
                                            "replica.zk.state#value",
                                            "replica.zk.last.processed.zxid#value",
                                            "replica.zk.latency#value{type=avg}",
                                            "replica.zk.latency#value{type=max}",
                                            "replica.zk.latency#value{type=min}",
                                            "replica.zk.log.dir.size#value",
                                            "replica.zk.nodes#value",
                                            "replica.zk.outstanding.requests#value",
                                            "replica.zk.packets.received#count",
                                            "replica.zk.packets.sent#count",
                                            "replica.zk.watches#value");
        }
    }

    @Test
    void setWriteQuota() throws Exception {
        try (Cluster cluster = Cluster.builder()
                                      .numReplicas(1)
                                      .build(ZooKeeperCommandExecutorTest::newMockDelegate)) {
            final String project = "test_prj";
            final String repo = "test_repo";
            final String key = project + '/' + repo;
            final Replica replica = cluster.get(0);
            final ZooKeeperCommandExecutor executor = replica.commandExecutor();
            final Cache<String, QuotaConfig> quotaCache = executor.writeQuotaCache;
            final ConcurrentMap<String, Entry<InterProcessSemaphoreV2, SettableSharedCount>> semaphoreMap =
                    executor.semaphoreMap;

            // Initial state
            QuotaConfig writeQuota = quotaCache.getIfPresent(key);
            Entry<InterProcessSemaphoreV2, SettableSharedCount> entry = semaphoreMap.get(key);
            assertThat(writeQuota).isNull();
            assertThat(entry).isNull();

            // Unlimited quota
            executor.setWriteQuota(project, repo, null);
            writeQuota = quotaCache.getIfPresent(key);
            entry = semaphoreMap.get(key);
            assertThat(writeQuota.requestQuota()).isEqualTo(Integer.MAX_VALUE);
            assertThat(entry.getKey()).isNull();
            assertThat(entry.getValue()).isNull();

            // Set a new quota
            QuotaConfig newQuota = new QuotaConfig(5, 1);
            executor.setWriteQuota(project, repo, newQuota);
            writeQuota = quotaCache.getIfPresent(key);
            entry = semaphoreMap.get(key);
            assertThat(writeQuota).isSameAs(newQuota);
            assertThat(entry.getValue().getCount()).isEqualTo(5);

            // Update the existing quota
            newQuota = new QuotaConfig(10, 1);
            executor.setWriteQuota(project, repo, newQuota);
            writeQuota = quotaCache.getIfPresent(key);
            final Entry<InterProcessSemaphoreV2, SettableSharedCount> entry2 = semaphoreMap.get(key);
            assertThat(writeQuota).isSameAs(newQuota);
            assertThat(entry2).isSameAs(entry);
            assertThat(entry2.getValue().getCount()).isEqualTo(10);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> Function<Command<?>, CompletableFuture<?>> newMockDelegate() {
        final Function<Command<T>, CompletableFuture<T>> delegate = mock(Function.class);
        final AtomicInteger revisionCounter = new AtomicInteger(1);
        lenient().when(delegate.apply(argThat(x -> x == null || x.type().resultType() == Void.class)))
                 .thenReturn(completedFuture(null));

        lenient().when(delegate.apply(argThat(x -> x != null && x.type().resultType() == Revision.class)))
                 .then(invocation -> completedFuture(new Revision(revisionCounter.incrementAndGet())));

        lenient().when(delegate.apply(argThat(x -> x != null && x.type().resultType() == CommitResult.class)))
                 .then(invocation -> {
                     final Revision revision = new Revision(revisionCounter.incrementAndGet());
                     final Object argument = invocation.getArgument(0);
                     if (argument instanceof NormalizingPushCommand) {
                         if (((NormalizingPushCommand) argument).changes().equals(
                                 ImmutableList.of(pushChange))) {
                            return completedFuture(
                                    CommitResult.of(revision, ImmutableList.of(normalizedChange)));
                         }
                     }
                     return completedFuture(CommitResult.of(revision, ImmutableList.of()));
                 });

        return (Function<Command<?>, CompletableFuture<?>>) (Function<?, ?>) delegate;
    }
}
