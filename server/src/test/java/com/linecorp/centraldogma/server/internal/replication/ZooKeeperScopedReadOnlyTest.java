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
package com.linecorp.centraldogma.server.internal.replication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.ReplicationStatus;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.UpdateProjectStatusCommand;
import com.linecorp.centraldogma.server.command.UpdateRepositoryStatusCommand;
import com.linecorp.centraldogma.server.command.UpdateServerStatusCommand;
import com.linecorp.centraldogma.server.management.ServerStatus;
import com.linecorp.centraldogma.testing.internal.FlakyTest;

/**
 * Verifies that a replication-time failure on a follower does not stop the executor;
 * instead, the read-only scope is contained to the project or repository that failed,
 * and the corresponding {@code Update*StatusCommand} is replicated to all replicas.
 */
@FlakyTest
class ZooKeeperScopedReadOnlyTest {

    private static final String PROJECT = "project";
    private static final String VICTIM_REPO = "victim";
    private static final String BYSTANDER_REPO = "bystander";
    private static final String SIBLING_REPO = "sibling";
    private static final long AWAIT_MILLIS = TimeUnit.SECONDS.toMillis(15);

    @Test
    void replayFailureOnRepoCommandScopesToRepo() throws Exception {
        try (Cluster cluster = Cluster.builder()
                                      .numReplicas(3)
                                      .build(ZooKeeperCommandExecutorTest::newMockDelegate)) {
            final Replica leader = cluster.get(0);
            final Replica failingFollower = cluster.get(1);
            final Replica healthyFollower = cluster.get(2);

            leader.commandExecutor().execute(Command.createProject(Author.SYSTEM, PROJECT)).join();
            leader.commandExecutor().execute(
                    Command.createRepository(Author.SYSTEM, PROJECT, VICTIM_REPO)).join();
            leader.commandExecutor().execute(
                    Command.createRepository(Author.SYSTEM, PROJECT, BYSTANDER_REPO)).join();

            // A deterministic timestamp + concrete base revision keeps the command
            // equal to the one stored in the log so eq() matches on replay.
            final Change<JsonNode> change = Change.ofJsonUpsert("/foo.json", "{\"a\":\"b\"}");
            final Command<Revision> victimPush = Command.push(
                    0L, Author.SYSTEM, PROJECT, VICTIM_REPO, new Revision(1),
                    "summary", "detail", Markup.PLAINTEXT, ImmutableList.of(change));
            doReturn(failedFuture("simulated replay failure"))
                    .when(failingFollower.delegate()).apply(eq(victimPush));

            leader.commandExecutor().execute(victimPush).join();

            // Every replica should see the scoped UpdateRepositoryStatusCommand.
            for (Replica r : cluster) {
                verify(r.delegate(), timeout(AWAIT_MILLIS)).apply(argThat(c -> {
                    if (!(c instanceof UpdateRepositoryStatusCommand)) {
                        return false;
                    }
                    final UpdateRepositoryStatusCommand u = (UpdateRepositoryStatusCommand) c;
                    return PROJECT.equals(u.projectName()) &&
                           VICTIM_REPO.equals(u.repoName()) &&
                           u.repoStatus() == ReplicationStatus.READ_ONLY;
                }));
            }

            // The failing follower must not have hard-stopped.
            assertThat(failingFollower.commandExecutor().isStarted()).isTrue();
            assertThat(healthyFollower.commandExecutor().isStarted()).isTrue();

            // The scope must be repo-only.
            verify(failingFollower.delegate(), never()).apply(argThat(
                    c -> c instanceof UpdateProjectStatusCommand ||
                         c instanceof UpdateServerStatusCommand));
        }
    }

    @Test
    void replayFailureOnProjectCommandScopesToProject() throws Exception {
        try (Cluster cluster = Cluster.builder()
                                      .numReplicas(3)
                                      .build(ZooKeeperCommandExecutorTest::newMockDelegate)) {
            final Replica leader = cluster.get(0);
            final Replica failingFollower = cluster.get(1);

            leader.commandExecutor().execute(Command.createProject(Author.SYSTEM, PROJECT)).join();

            // CreateRepositoryCommand is a ProjectCommand → scopes to project on failure.
            final Command<Void> createRepo = Command.createRepository(
                    Author.SYSTEM, PROJECT, VICTIM_REPO);
            doReturn(failedFuture("simulated replay failure"))
                    .when(failingFollower.delegate()).apply(eq(createRepo));

            leader.commandExecutor().execute(createRepo).join();

            for (Replica r : cluster) {
                verify(r.delegate(), timeout(AWAIT_MILLIS)).apply(argThat(c -> {
                    if (!(c instanceof UpdateProjectStatusCommand)) {
                        return false;
                    }
                    final UpdateProjectStatusCommand u = (UpdateProjectStatusCommand) c;
                    return PROJECT.equals(u.projectName()) &&
                           u.projectStatus() == ReplicationStatus.READ_ONLY;
                }));
            }

            assertThat(failingFollower.commandExecutor().isStarted()).isTrue();
            verify(failingFollower.delegate(), never()).apply(argThat(
                    c -> c instanceof UpdateRepositoryStatusCommand ||
                         c instanceof UpdateServerStatusCommand));
        }
    }

    @Test
    void replayFailureOnRootCommandScopesToServer() throws Exception {
        try (Cluster cluster = Cluster.builder()
                                      .numReplicas(3)
                                      .build(ZooKeeperCommandExecutorTest::newMockDelegate)) {
            final Replica leader = cluster.get(0);
            final Replica failingFollower = cluster.get(1);

            // CreateProjectCommand is a RootCommand → scopes to server on failure.
            final Command<Void> createProject = Command.createProject(Author.SYSTEM, PROJECT);
            doReturn(failedFuture("simulated replay failure"))
                    .when(failingFollower.delegate()).apply(eq(createProject));

            leader.commandExecutor().execute(createProject).join();

            for (Replica r : cluster) {
                verify(r.delegate(), timeout(AWAIT_MILLIS)).apply(argThat(c -> {
                    if (!(c instanceof UpdateServerStatusCommand)) {
                        return false;
                    }
                    final UpdateServerStatusCommand u = (UpdateServerStatusCommand) c;
                    return u.serverStatus() == ServerStatus.REPLICATION_ONLY;
                }));
            }

            // The failing follower stays running — only its writability flips via the command.
            assertThat(failingFollower.commandExecutor().isStarted()).isTrue();
        }
    }

    @Test
    void replayFailureOnUpdateRepositoryStatusCommandEscalatesToServer() throws Exception {
        // UpdateRepositoryStatusCommand persists its effect by writing to dogma/dogma, so a
        // replay failure means dogma/dogma diverged on the follower. handleReplicationFailure
        // must therefore escalate to a server-wide REPLICATION_ONLY (it cannot trust dogma/dogma
        // on that replica anymore).
        try (Cluster cluster = Cluster.builder()
                                      .numReplicas(3)
                                      .build(ZooKeeperCommandExecutorTest::newMockDelegate)) {
            final Replica leader = cluster.get(0);
            final Replica failingFollower = cluster.get(1);

            leader.commandExecutor().execute(Command.createProject(Author.SYSTEM, PROJECT)).join();
            leader.commandExecutor().execute(
                    Command.createRepository(Author.SYSTEM, PROJECT, VICTIM_REPO)).join();

            final Command<Void> statusUpdate = Command.updateRepositoryStatus(
                    PROJECT, VICTIM_REPO, ReplicationStatus.READ_ONLY);
            doReturn(failedFuture("simulated replay failure"))
                    .when(failingFollower.delegate()).apply(eq(statusUpdate));

            leader.commandExecutor().execute(statusUpdate).join();

            for (Replica r : cluster) {
                verify(r.delegate(), timeout(AWAIT_MILLIS)).apply(argThat(c -> {
                    if (!(c instanceof UpdateServerStatusCommand)) {
                        return false;
                    }
                    final UpdateServerStatusCommand u = (UpdateServerStatusCommand) c;
                    return u.serverStatus() == ServerStatus.REPLICATION_ONLY;
                }));
            }
            assertThat(failingFollower.commandExecutor().isStarted()).isTrue();
        }
    }

    @Test
    void siblingRepoStillReplicatedAfterRepoScopedReadOnly() throws Exception {
        try (Cluster cluster = Cluster.builder()
                                      .numReplicas(3)
                                      .build(ZooKeeperCommandExecutorTest::newMockDelegate)) {
            final Replica leader = cluster.get(0);
            final Replica failingFollower = cluster.get(1);

            leader.commandExecutor().execute(Command.createProject(Author.SYSTEM, PROJECT)).join();
            leader.commandExecutor().execute(
                    Command.createRepository(Author.SYSTEM, PROJECT, VICTIM_REPO)).join();
            leader.commandExecutor().execute(
                    Command.createRepository(Author.SYSTEM, PROJECT, BYSTANDER_REPO)).join();

            final Change<JsonNode> change = Change.ofJsonUpsert("/foo.json", "{\"a\":\"b\"}");
            final Command<Revision> victimPush = Command.push(
                    0L, Author.SYSTEM, PROJECT, VICTIM_REPO, new Revision(1),
                    "summary", "detail", Markup.PLAINTEXT, ImmutableList.of(change));
            doReturn(failedFuture("simulated replay failure"))
                    .when(failingFollower.delegate()).apply(eq(victimPush));

            leader.commandExecutor().execute(victimPush).join();

            // Wait until the victim repo's read-only command lands on the failing follower.
            verify(failingFollower.delegate(), timeout(AWAIT_MILLIS)).apply(argThat(c -> {
                if (!(c instanceof UpdateRepositoryStatusCommand)) {
                    return false;
                }
                final UpdateRepositoryStatusCommand u = (UpdateRepositoryStatusCommand) c;
                return VICTIM_REPO.equals(u.repoName());
            }));

            // A push to the bystander repo must still be replicated everywhere.
            final Command<Revision> bystanderPush = Command.push(
                    0L, Author.SYSTEM, PROJECT, BYSTANDER_REPO, new Revision(1),
                    "summary", "detail", Markup.PLAINTEXT, ImmutableList.of(change));
            leader.commandExecutor().execute(bystanderPush).join();

            for (Replica r : cluster) {
                verify(r.delegate(), timeout(AWAIT_MILLIS)).apply(eq(bystanderPush));
            }
        }
    }

    @Test
    void replayContinuesPastFailedLogToLaterRepos() throws Exception {
        try (Cluster cluster = Cluster.builder()
                                      .numReplicas(3)
                                      .build(ZooKeeperCommandExecutorTest::newMockDelegate)) {
            final Replica leader = cluster.get(0);
            final Replica failingFollower = cluster.get(1);

            leader.commandExecutor().execute(Command.createProject(Author.SYSTEM, PROJECT)).join();
            leader.commandExecutor().execute(
                    Command.createRepository(Author.SYSTEM, PROJECT, VICTIM_REPO)).join();
            leader.commandExecutor().execute(
                    Command.createRepository(Author.SYSTEM, PROJECT, BYSTANDER_REPO)).join();
            leader.commandExecutor().execute(
                    Command.createRepository(Author.SYSTEM, PROJECT, SIBLING_REPO)).join();

            final Change<JsonNode> change = Change.ofJsonUpsert("/foo.json", "{\"a\":\"b\"}");
            final Command<Revision> victimPush = Command.push(
                    0L, Author.SYSTEM, PROJECT, VICTIM_REPO, new Revision(1),
                    "summary", "detail", Markup.PLAINTEXT, ImmutableList.of(change));
            doReturn(failedFuture("simulated replay failure"))
                    .when(failingFollower.delegate()).apply(eq(victimPush));

            // The victim push fails to replay on the failing follower; the two later pushes must still run.
            final Command<Revision> bystanderPush = Command.push(
                    0L, Author.SYSTEM, PROJECT, BYSTANDER_REPO, new Revision(1),
                    "summary", "detail", Markup.PLAINTEXT, ImmutableList.of(change));
            final Command<Revision> siblingPush = Command.push(
                    0L, Author.SYSTEM, PROJECT, SIBLING_REPO, new Revision(1),
                    "summary", "detail", Markup.PLAINTEXT, ImmutableList.of(change));
            leader.commandExecutor().execute(victimPush).join();
            leader.commandExecutor().execute(bystanderPush).join();
            leader.commandExecutor().execute(siblingPush).join();

            // The failure is contained to the victim repo on every replica.
            for (Replica r : cluster) {
                verify(r.delegate(), timeout(AWAIT_MILLIS)).apply(argThat(c -> {
                    if (!(c instanceof UpdateRepositoryStatusCommand)) {
                        return false;
                    }
                    final UpdateRepositoryStatusCommand u = (UpdateRepositoryStatusCommand) c;
                    return PROJECT.equals(u.projectName()) &&
                           VICTIM_REPO.equals(u.repoName()) &&
                           u.repoStatus() == ReplicationStatus.READ_ONLY;
                }));
            }

            // Replay continued past the failed log: both later pushes reach every replica,
            // including the follower that failed on the victim push.
            for (Replica r : cluster) {
                verify(r.delegate(), timeout(AWAIT_MILLIS)).apply(eq(bystanderPush));
                verify(r.delegate(), timeout(AWAIT_MILLIS)).apply(eq(siblingPush));
            }

            // The failing follower stays up and never escalates beyond the repository scope.
            assertThat(failingFollower.commandExecutor().isStarted()).isTrue();
            verify(failingFollower.delegate(), never()).apply(argThat(
                    c -> c instanceof UpdateProjectStatusCommand ||
                         c instanceof UpdateServerStatusCommand));
        }
    }

    private static CompletableFuture<?> failedFuture(String message) {
        return CompletableFuture.failedFuture(new RuntimeException(message));
    }
}
