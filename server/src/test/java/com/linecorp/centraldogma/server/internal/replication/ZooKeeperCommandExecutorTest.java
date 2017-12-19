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

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.command.AbstractCommandExecutor;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.replication.ZooKeeperCommandExecutor.Builder;

public class ZooKeeperCommandExecutorTest {

    private static final int NUM_REPLICAS = Runtime.getRuntime().availableProcessors() * 2;
    private static final String ROOT = "/root";

    @Rule
    public TemporaryFolder testFolder = new TemporaryFolder();

    private TestingServer zkServer;

    @Before
    public void setUp() throws Exception {
        zkServer = new TestingServer(new InstanceSpec(
                testFolder.newFolder(), -1, -1, -1, true, -1, -1, NUM_REPLICAS), true);
    }

    @After
    public void cleanUp() throws IOException {
        if (zkServer != null) {
            zkServer.close();
        }
    }

    @Test
    public void testLogWatch() throws Exception {
        final Replica replica1 = new Replica("m1", ROOT);
        final Replica replica2 = new Replica("m2", ROOT);
        final Replica replica3 = new Replica("m3", ROOT);

        Replica newReplica3 = null;
        Replica replica4 = null;

        try {
            final Command<Void> command1 = Command.createRepository(Author.SYSTEM, "project", "repo1");
            replica1.rm.execute(command1).join();

            final Optional<ReplicationLog<?>> commandResult2 = replica1.rm.loadLog(0, false);
            assertThat(commandResult2.get().command()).isEqualTo(command1);
            assertThat(commandResult2.get().result()).isNull();

            Thread.sleep(100);
            verify(replica1.delegate, timeout(300).times(1)).apply(eq(command1));
            verify(replica2.delegate, timeout(300).times(1)).apply(eq(command1));
            verify(replica3.delegate, timeout(300).times(1)).apply(eq(command1));

            assertThat(replica1.localRevision()).isEqualTo(0L);
            assertThat(replica2.localRevision()).isEqualTo(0L);
            assertThat(replica3.localRevision()).isEqualTo(0L);

            //stop replay m3
            replica3.rm.stop();

            final Command<?> command2 = Command.createProject(Author.SYSTEM, "foo");
            replica1.rm.execute(command2).join();
            verify(replica1.delegate, timeout(300).times(1)).apply(eq(command2));
            verify(replica2.delegate, timeout(300).times(1)).apply(eq(command2));
            verify(replica3.delegate, timeout(300).times(0)).apply(eq(command2));

            //start replay m3 with new object
            newReplica3 = new Replica("m3", ROOT);
            verify(newReplica3.delegate, timeout(TimeUnit.SECONDS.toMillis(2)).times(1)).apply(eq(command1));
            verify(newReplica3.delegate, timeout(TimeUnit.SECONDS.toMillis(2)).times(1)).apply(eq(command2));

            replica4 = new Replica("m4", ROOT);
            verify(replica4.delegate, timeout(TimeUnit.SECONDS.toMillis(2)).times(1)).apply(eq(command1));
            verify(replica4.delegate, timeout(TimeUnit.SECONDS.toMillis(2)).times(1)).apply(eq(command2));
        } finally {
            replica1.rm.stop();
            replica2.rm.stop();
            if (newReplica3 != null) {
                newReplica3.rm.stop();
            }
            if (replica4 != null) {
                replica4.rm.stop();
            }
        }
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
        final Replica[] replicas = new Replica[NUM_REPLICAS];
        for (int i = 0; i < replicas.length; i++) {
            final AtomicInteger counter = new AtomicInteger();
            replicas[i] = new Replica(String.valueOf(i), ROOT,
                                      command -> completedFuture(new Revision(counter.incrementAndGet())));
        }

        try {
            final Command<Revision> command =
                    Command.push(Author.SYSTEM, "foo", "bar", Revision.HEAD, "", "", Markup.PLAINTEXT);

            final int COMMANDS_PER_REPLICA = 3;
            final List<CompletableFuture<Void>> futures = new ArrayList<>(replicas.length);
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
                for (int i = 0; i < COMMANDS_PER_REPLICA * replicas.length; i++) {
                    @SuppressWarnings("unchecked")
                    final ReplicationLog<Revision> log =
                            (ReplicationLog<Revision>) r.rm.loadLog(i, false).get();

                    assertThat(log.result().major()).isEqualTo(i + 1);
                }
            }
        } finally {
            for (Replica r : replicas) {
                r.rm.stop();
            }
        }
    }

    private static Function<Command<?>, CompletableFuture<?>> newMockDelegate() {
        @SuppressWarnings("unchecked")
        final Function<Command<?>, CompletableFuture<?>> delegate = mock(Function.class);
        when(delegate.apply(any())).thenReturn(completedFuture(null));
        return delegate;
    }

    private final class Replica {

        private final ZooKeeperCommandExecutor rm;
        private final Function<Command<?>, CompletableFuture<?>> delegate;
        private final File revisionFile;

        @SuppressWarnings("unchecked")
        Replica(String id, String zkPath) throws Exception {
            this(id, zkPath, newMockDelegate());
        }

        Replica(String id, String zkPath,
                Function<Command<?>, CompletableFuture<?>> delegate) throws Exception {

            this.delegate = delegate;
            revisionFile = testFolder.newFile();

            final Builder builder = ZooKeeperCommandExecutor.builder();
            rm = builder.replicaId(id)
                        .delegate(new AbstractCommandExecutor(id) {
                            @Override
                            protected void doStart(@Nullable Runnable onTakeLeadership,
                                                   @Nullable Runnable onReleaseLeadership) {}

                            @Override
                            protected void doStop() {}

                            @Override
                            @SuppressWarnings("unchecked")
                            protected <T> CompletableFuture<T> doExecute(String replicaId, Command<T> command) {
                                return (CompletableFuture<T>) delegate.apply(command);
                            }
                        })
                        .connectionString("127.0.0.1:" + zkServer.getPort())
                        .path(zkPath)
                        .createPathIfNotExist(true)
                        .revisionFile(revisionFile)
                        .build();

            rm.start(null, null);
        }

        long localRevision() throws IOException {
            try (BufferedReader br =
                         new BufferedReader(new InputStreamReader(new FileInputStream(revisionFile)))) {
                return Long.parseLong(br.readLine());
            }
        }
    }
}
