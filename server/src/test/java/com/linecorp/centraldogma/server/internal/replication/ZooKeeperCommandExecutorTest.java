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
package com.linecorp.centraldogma.server.internal.replication;

import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.apache.curator.test.InstanceSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.ZooKeeperAddress;
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfig;
import com.linecorp.centraldogma.server.command.AbstractCommandExecutor;
import com.linecorp.centraldogma.server.command.Command;

import io.micrometer.core.instrument.MeterRegistry;

public class ZooKeeperCommandExecutorTest {

    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperCommandExecutorTest.class);

    private static final int NUM_REPLICAS = 5;

    @Rule
    public final TestRule timeoutRule = new DisableOnDebug(new Timeout(1, TimeUnit.MINUTES));

    @Rule
    public final TemporaryFolder testFolder = new TemporaryFolder();

    @Test
    public void testLogWatch() throws Exception {
        // The 5th replica is used for ensuring the quorum.
        final List<Replica> cluster = buildCluster(5, true /* start */,
                                                   ZooKeeperCommandExecutorTest::newMockDelegate);
        final Replica replica1 = cluster.get(0);
        final Replica replica2 = cluster.get(1);
        final Replica replica3 = cluster.get(2);
        final Replica replica4 = cluster.get(3);
        replica4.rm.stop().join();

        try {
            final Command<Void> command1 = Command.createRepository(Author.SYSTEM, "project", "repo1");
            replica1.rm.execute(command1).join();

            final Optional<ReplicationLog<?>> commandResult2 = replica1.rm.loadLog(0, false);
            assertThat(commandResult2.get().command()).isEqualTo(command1);
            assertThat(commandResult2.get().result()).isNull();

            await().untilAsserted(() -> verify(replica1.delegate).apply(eq(command1)));
            await().untilAsserted(() -> verify(replica2.delegate).apply(eq(command1)));
            await().untilAsserted(() -> verify(replica3.delegate).apply(eq(command1)));

            await().until(replica1::existsLocalRevision);
            await().until(replica2::existsLocalRevision);
            await().until(replica3::existsLocalRevision);

            assertThat(replica1.localRevision()).isEqualTo(0L);
            assertThat(replica2.localRevision()).isEqualTo(0L);
            assertThat(replica3.localRevision()).isEqualTo(0L);

            // Stop the 3rd replica and check if the 1st and 2nd replicas still replay the logs.
            replica3.rm.stop().join();

            final Command<?> command2 = Command.createProject(Author.SYSTEM, "foo");
            replica1.rm.execute(command2).join();
            await().untilAsserted(() -> verify(replica1.delegate).apply(eq(command2)));
            await().untilAsserted(() -> verify(replica2.delegate).apply(eq(command2)));
            await().untilAsserted(() -> verify(replica3.delegate, times(0)).apply(eq(command2)));

            // Start the 3rd replica back again and check if it catches up.
            replica3.rm.start().join();
            verifyTwoIndependentCommands(replica3, command1, command2);

            // Start the 4th replica and check if it catches up even if it started from scratch.
            replica4.rm.start().join();
            verifyTwoIndependentCommands(replica4, command1, command2);
        } finally {
            for (Replica r : cluster) {
                r.rm.stop();
            }
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
        verify(replica.delegate, timeout(TimeUnit.SECONDS.toMillis(2)).times(1)).apply(argThat(c -> {
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
        verify(replica.delegate, timeout(TimeUnit.SECONDS.toMillis(2)).times(1)).apply(argThat(c -> {
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
    public void testRace() throws Exception {
        // Each replica has its own AtomicInteger which counts the number of commands
        // it executed/replayed so far.
        final List<Replica> replicas = buildCluster(NUM_REPLICAS, true /* start */, () -> {
            final AtomicInteger counter = new AtomicInteger();
            return command -> completedFuture(new Revision(counter.incrementAndGet()));
        });

        try {
            final Command<Revision> command =
                    Command.push(Author.SYSTEM, "foo", "bar", Revision.HEAD, "", "", Markup.PLAINTEXT);

            final int COMMANDS_PER_REPLICA = 7;
            final List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (final Replica r : replicas) {
                futures.add(CompletableFuture.runAsync(() -> {
                    for (int j = 0; j < COMMANDS_PER_REPLICA; j++) {
                        try {
                            r.rm.execute(command).join();
                        } catch (Exception e) {
                            throw new Error(e);
                        }
                    }
                }));
            }

            for (CompletableFuture<Void> f : futures) {
                f.get();
            }

            for (Replica r : replicas) {
                for (int i = 0; i < COMMANDS_PER_REPLICA * replicas.size(); i++) {
                    @SuppressWarnings("unchecked")
                    final ReplicationLog<Revision> log =
                            (ReplicationLog<Revision>) r.rm.loadLog(i, false).get();

                    assertThat(log.result().major()).isEqualTo(i + 1);
                }
            }
        } finally {
            replicas.forEach(r -> r.rm.stop());
        }
    }

    /**
     * Makes sure that we can stop a replica that's waiting for the initial quorum.
     */
    @Test
    public void stopWhileWaitingForInitialQuorum() throws Exception {
        final List<Replica> cluster = buildCluster(2, false /* start */,
                                                   ZooKeeperCommandExecutorTest::newMockDelegate);

        final CompletableFuture<Void> startFuture = cluster.get(0).rm.start();
        cluster.get(0).rm.stop().join();

        assertThat(startFuture).hasFailedWithThrowableThat()
                               .isInstanceOf(InterruptedException.class)
                               .hasMessageContaining("before joining");
    }

    @Test
    public void metrics() throws Exception {
        final List<Replica> cluster = buildCluster(1, true /* start */,
                                                   ZooKeeperCommandExecutorTest::newMockDelegate);
        try {
            final Map<String, Double> meters = MoreMeters.measureAll(cluster.get(0).meterRegistry);
            assertThat(meters).containsKeys("executor.pool.size#value{name=zkCommandExecutor}",
                                            "executor.pool.size#value{name=zkLeaderSelector}",
                                            "executor.pool.size#value{name=zkLogWatcher}",
                                            "zookeeper.aliveClientConnections#value",
                                            "zookeeper.dataDirSize#value",
                                            "zookeeper.state.leader#value",
                                            "zookeeper.state.follower#value",
                                            "zookeeper.state.observer#value",
                                            "zookeeper.state.readOnly#value",
                                            "zookeeper.state.unknown#value",
                                            "zookeeper.lastProcessedZxid#value",
                                            "zookeeper.latency#value{type=avg}",
                                            "zookeeper.latency#value{type=max}",
                                            "zookeeper.latency#value{type=min}",
                                            "zookeeper.logDirSize#value",
                                            "zookeeper.outstandingRequests#value",
                                            "zookeeper.packetsReceived#count",
                                            "zookeeper.packetsSent#count");
        } finally {
            cluster.forEach(r -> r.rm.stop());
        }
    }

    private List<Replica> buildCluster(
            int numReplicas, boolean start,
            Supplier<Function<Command<?>, CompletableFuture<?>>> delegateSupplier) throws Exception {

        // Each replica requires 3 ports, for client, quorum and election.
        final List<ServerSocket> quorumPorts = new ArrayList<>();
        final List<ServerSocket> electionPorts = new ArrayList<>();
        for (int i = 0; i < numReplicas; i++) {
            quorumPorts.add(new ServerSocket(0));
            electionPorts.add(new ServerSocket(0));
        }

        final List<InstanceSpec> specs = new ArrayList<>();
        final Map<Integer, ZooKeeperAddress> servers = new HashMap<>();
        for (int i = 0; i < numReplicas; i++) {
            final InstanceSpec spec = new InstanceSpec(
                    testFolder.newFolder(),
                    0,
                    electionPorts.get(i).getLocalPort(),
                    quorumPorts.get(i).getLocalPort(),
                    false,
                    i + 1 /* serverId */);

            specs.add(spec);
            servers.put(spec.getServerId(),
                        new ZooKeeperAddress("127.0.0.1", spec.getQuorumPort(), spec.getElectionPort(), 0));
        }

        logger.debug("Creating a cluster: {}", servers);

        for (int i = 0; i < numReplicas; i++) {
            quorumPorts.get(i).close();
            electionPorts.get(i).close();
        }

        final ImmutableList.Builder<Replica> builder = ImmutableList.builder();
        for (InstanceSpec spec : specs) {
            final Replica r = new Replica(spec, servers, delegateSupplier.get(), start);
            builder.add(r);
        }

        final List<Replica> replicas = builder.build();
        if (start) {
            replicas.forEach(Replica::awaitStartup);
        }
        return replicas;
    }

    private static Function<Command<?>, CompletableFuture<?>> newMockDelegate() {
        @SuppressWarnings("unchecked")
        final Function<Command<?>, CompletableFuture<?>> delegate = mock(Function.class);
        when(delegate.apply(any())).thenReturn(completedFuture(null));
        return delegate;
    }

    private static final class Replica {

        private final ZooKeeperCommandExecutor rm;
        private final Function<Command<?>, CompletableFuture<?>> delegate;
        private final File dataDir;
        private final MeterRegistry meterRegistry;
        private final CompletableFuture<Void> startFuture;

        Replica(InstanceSpec spec, Map<Integer, ZooKeeperAddress> servers,
                Function<Command<?>, CompletableFuture<?>> delegate, boolean start) throws Exception {
            this.delegate = delegate;

            dataDir = spec.getDataDirectory();
            meterRegistry = PrometheusMeterRegistries.newRegistry();

            final int id = spec.getServerId();
            final ZooKeeperReplicationConfig zkCfg = new ZooKeeperReplicationConfig(id, servers);

            rm = new ZooKeeperCommandExecutor(zkCfg, dataDir, new AbstractCommandExecutor(null, null) {
                @Override
                public int replicaId() {
                    return id;
                }

                @Override
                protected void doStart(@Nullable Runnable onTakeLeadership,
                                       @Nullable Runnable onReleaseLeadership) {}

                @Override
                protected void doStop(@Nullable Runnable onReleaseLeadership) {}

                @Override
                @SuppressWarnings("unchecked")
                protected <T> CompletableFuture<T> doExecute(Command<T> command) {
                    return (CompletableFuture<T>) delegate.apply(command);
                }
            }, meterRegistry, null, null);

            startFuture = start ? rm.start() : null;
        }

        MeterRegistry meterRegistry() {
            return meterRegistry;
        }

        void awaitStartup() {
            checkState(startFuture != null);
            startFuture.join();
        }

        long localRevision() throws IOException {
            return await().ignoreExceptions().until(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        new FileInputStream(new File(dataDir, "last_revision"))))) {
                    return Long.parseLong(br.readLine());
                }
            }, Objects::nonNull);
        }

        boolean existsLocalRevision() {
            return Files.isReadable(new File(dataDir, "last_revision").toPath());
        }
    }
}
