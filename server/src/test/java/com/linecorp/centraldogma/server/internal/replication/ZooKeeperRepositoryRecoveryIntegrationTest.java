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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.ReplicationStatus;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.jsonpatch.JsonPatchOperation;
import com.linecorp.centraldogma.internal.api.v1.RepositoryDto;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.ReplayCommit;
import com.linecorp.centraldogma.server.command.RepositoryCommand;
import com.linecorp.centraldogma.server.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.api.RecoverRepositoryRequest;
import com.linecorp.centraldogma.server.internal.api.UpdateRepositoryStatusRequest;
import com.linecorp.centraldogma.server.internal.api.sysadmin.UpdateServerStatusRequest;
import com.linecorp.centraldogma.server.internal.api.sysadmin.UpdateServerStatusRequest.Scope;
import com.linecorp.centraldogma.server.management.ServerStatus;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;
import com.linecorp.centraldogma.testing.internal.CentralDogmaReplicationExtension;

/**
 * Drives a repository into a truly diverged state (one replica applied a command the others did not) and
 * verifies that {@code POST /api/v1/projects/{p}/repos/{r}/recover} reconverges every replica onto the
 * designated source replica's history — through both the direct (endpoint on the source) and the
 * request-and-react (endpoint on a non-source replica) paths.
 */
class ZooKeeperRepositoryRecoveryIntegrationTest {

    private static final String TEST_REPO = "test-repo";
    private static final AtomicInteger testCounter = new AtomicInteger();
    private static final FaultInjector faultInjector = new FaultInjector();

    // serverId is 1-based while servers() is 0-based: serverById(2) == servers().get(1).
    private static final int DIVERGED_SERVER_ID = 2;
    private static final int SOURCE_SERVER_ID = 1;

    @RegisterExtension
    static final CentralDogmaReplicationExtension replica = new CentralDogmaReplicationExtension(3) {
        @Override
        protected void configureEach(int serverId, CentralDogmaBuilder builder) {
            if (serverId == DIVERGED_SERVER_ID) {
                builder.plugins(faultInjector);
            }
        }
    };

    private static CentralDogma client0;

    private String testProject;

    @BeforeEach
    void beforeEach() {
        client0 = replica.servers().get(0).client();

        // Reset a server-wide REPLICATION_ONLY escalation from a previous test, if any.
        replica.servers().get(0).blockingHttpClient()
               .prepare()
               .put("/api/v1/status")
               .contentJson(new UpdateServerStatusRequest(ServerStatus.WRITABLE, Scope.ALL))
               .execute();
        for (int i = 0; i < 3; i++) {
            final BlockingWebClient adminClient = replica.servers().get(i).blockingHttpClient();
            await().untilAsserted(() -> assertThat(getServerStatus(adminClient))
                    .isEqualTo(ServerStatus.WRITABLE));
        }

        testProject = "recovery-project-" + testCounter.incrementAndGet();
        client0.createProject(testProject).join();
        client0.createRepository(testProject, TEST_REPO).join();
    }

    @Test
    void recoverDivergedReplicaViaSourceReplica() {
        driveRepoIntoDivergedReadOnly();

        // POST the recovery to the source replica itself; it builds the payload and originates directly.
        final AggregatedHttpResponse response =
                recover(adminClientOf(SOURCE_SERVER_ID), new RecoverRepositoryRequest(3, SOURCE_SERVER_ID));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).contains("\"COMPLETED\"");
        assertThat(response.contentUtf8()).contains("\"headRevision\":3");

        // Recovery is idempotent: running it again converges to the same head and changes nothing.
        final AggregatedHttpResponse second =
                recover(adminClientOf(SOURCE_SERVER_ID), new RecoverRepositoryRequest(3, SOURCE_SERVER_ID));
        assertThat(second.status()).isEqualTo(HttpStatus.OK);
        assertThat(second.contentUtf8()).contains("\"COMPLETED\"");
        assertThat(second.contentUtf8()).contains("\"headRevision\":3");

        assertClusterConvergedAndUsable();
    }

    @Test
    void recoverRejectedForOutOfRangeFromRevision() {
        driveRepoIntoDivergedReadOnly();

        // The source head is r3, so replaying from r99 is impossible; the direct path surfaces it as 400.
        final AggregatedHttpResponse response =
                recover(adminClientOf(SOURCE_SERVER_ID), new RecoverRepositoryRequest(99, SOURCE_SERVER_ID));
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).contains("fromRevision");
    }

    @Test
    void recoverAbortsWhenRepositoryIsNotReadOnly() {
        // Bypass the endpoint precondition and apply the command directly: the apply-time re-check must
        // reject it, so a recovery can never silently discard commits of a writable repository.
        final CentralDogmaRepository repo = client0.forRepo(testProject, TEST_REPO);
        repo.commit("seed", Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }")).push().join();
        final Revision headBefore = repo.normalize(Revision.HEAD).join();

        final Command<Revision> recoverCommand = Command.recoverRepository(
                Author.SYSTEM, testProject, TEST_REPO, SOURCE_SERVER_ID, new Revision(1), new Revision(2),
                ImmutableList.of(new ReplayCommit(
                        new Revision(2), 2000L, Author.SYSTEM, "seed", "", Markup.PLAINTEXT,
                        ImmutableList.of(Change.ofJsonUpsert("/a.json", "{ \"a\": 9 }")), null)));
        assertThatThrownBy(() -> faultInjector.zkExecute(recoverCommand))
                .hasStackTraceContaining("no longer read-only");

        // Nothing changed and the repository stays writable.
        assertThat(repo.normalize(Revision.HEAD).join()).isEqualTo(headBefore);
        assertThat(jsonValueOn(SOURCE_SERVER_ID, "a")).isEqualTo(1);
        assertThat(getRepoStatus(adminClientOf(SOURCE_SERVER_ID), testProject, TEST_REPO).status())
                .isEqualTo(ReplicationStatus.WRITABLE);
        repo.commit("after", Change.ofJsonUpsert("/a.json", "{ \"a\": 2 }")).push().join();
    }

    @Test
    void replicasEndpointListsClusterRoster() {
        for (int serverId = 1; serverId <= 3; serverId++) {
            final ResponseEntity<JsonNode> response = adminClientOf(serverId)
                    .prepare()
                    .get("/api/v1/replicas")
                    .asJson(JsonNode.class)
                    .execute();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            final JsonNode replicas = response.content();
            assertThat(replicas.size()).isEqualTo(3);
            int currentCount = 0;
            for (JsonNode replica : replicas) {
                assertThat(replica.get("host").asText()).isNotEmpty();
                if (replica.get("current").asBoolean()) {
                    currentCount++;
                    // The replica marked as current is the one that served the request.
                    assertThat(replica.get("serverId").asInt()).isEqualTo(serverId);
                }
            }
            assertThat(currentCount).isEqualTo(1);
        }
    }

    @Test
    void recoverDivergedReplicaViaNonSourceReplica() {
        driveRepoIntoDivergedReadOnly();

        // POST the recovery to the diverged, non-source replica; it asks the source over the replication
        // log and the source reacts by originating the actual recovery command.
        final AggregatedHttpResponse response =
                recover(adminClientOf(DIVERGED_SERVER_ID), new RecoverRepositoryRequest(2, SOURCE_SERVER_ID));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).contains("\"REQUESTED\"");

        assertClusterConvergedAndUsable();
    }

    @Test
    void recoverRejectedWhileWritable() {
        // The repository is writable; recovery must be rejected so that no concurrent push can race the
        // payload build.
        final AggregatedHttpResponse response =
                recover(adminClientOf(SOURCE_SERVER_ID), new RecoverRepositoryRequest(2, SOURCE_SERVER_ID));
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).contains("read-only");
    }

    @Test
    void recoverRejectedForUnknownSourceServer() {
        driveRepoIntoDivergedReadOnly();

        final AggregatedHttpResponse response =
                recover(adminClientOf(SOURCE_SERVER_ID), new RecoverRepositoryRequest(2, 42));
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).contains("sourceServerId");
    }

    /**
     * Produces the recovery scenario: the fault-injected replica applies an extra commit directly (not via
     * the replication log), so replaying the next replicated commit fails there and the repository goes
     * read-only cluster-wide, with the fault-injected replica truly diverged from the others.
     *
     * <p>Source history: r1 (creation), r2 {@code {"a": 1}}, r3 {@code {"a": 2}}. Diverged replica:
     * r1, r2 and a local r3 {@code {"a": 3}}; the legitimate r3 was skipped.
     */
    private void driveRepoIntoDivergedReadOnly() {
        final CentralDogmaRepository repo = client0.forRepo(testProject, TEST_REPO);
        repo.commit("seed", Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }")).push().join();

        faultInjector.injectFault(Command.push(
                Author.DEFAULT, testProject, TEST_REPO, Revision.HEAD,
                "inject fault", "", Markup.PLAINTEXT,
                ImmutableList.of(Change.ofJsonUpsert("/a.json", "{ \"a\": 3 }"))));

        repo.commit("divergence",
                    Change.ofJsonPatch("/a.json",
                                       JsonPatchOperation.safeReplace("/a", new IntNode(1), new IntNode(2))))
            .push()
            .join();

        // The failed replay escalates the repository into read-only cluster-wide. Assert it on a writable
        // replica; a locally read-only replica composes its server status into every per-repo status.
        await().untilAsserted(() -> assertThat(
                getRepoStatus(adminClientOf(SOURCE_SERVER_ID), testProject, TEST_REPO).status())
                .isEqualTo(ReplicationStatus.READ_ONLY));

        // The diverged replica really diverged: it holds its locally applied content instead of the
        // replicated one.
        await().ignoreExceptions().untilAsserted(() -> assertThat(
                jsonValueOn(DIVERGED_SERVER_ID, "a")).isEqualTo(3));
        assertThat(jsonValueOn(SOURCE_SERVER_ID, "a")).isEqualTo(2);
    }

    /**
     * Asserts the post-recovery invariants: every replica converged to the source history, the repository
     * stayed read-only until made writable, and afterwards a new push replicates cleanly to all replicas
     * with no new read-only escalation.
     */
    private void assertClusterConvergedAndUsable() {
        // Every replica converges to the source content at the source head.
        for (int serverId = 1; serverId <= 3; serverId++) {
            final int id = serverId;
            await().ignoreExceptions().untilAsserted(() -> {
                assertThat(headRevisionOn(id).major()).isEqualTo(3);
                assertThat(jsonValueOn(id, "a")).isEqualTo(2);
            });
        }

        // The repository stays read-only after recovery until the operator makes it writable.
        assertThat(getRepoStatus(adminClientOf(SOURCE_SERVER_ID), testProject, TEST_REPO).status())
                .isEqualTo(ReplicationStatus.READ_ONLY);
        final ResponseEntity<RepositoryDto> writableRes =
                adminClientOf(SOURCE_SERVER_ID)
                        .prepare()
                        .put("/api/v1/projects/{project}/repos/{repo}/status")
                        .pathParam("project", testProject)
                        .pathParam("repo", TEST_REPO)
                        .contentJson(new UpdateRepositoryStatusRequest(ReplicationStatus.WRITABLE))
                        .asJson(RepositoryDto.class)
                        .execute();
        assertThat(writableRes.status()).isEqualTo(HttpStatus.OK);

        // A new push replays cleanly on every replica — including the recovered one — and triggers no new
        // read-only escalation.
        client0.forRepo(testProject, TEST_REPO)
               .commit("after-recovery", Change.ofJsonUpsert("/a.json", "{ \"a\": 4 }"))
               .push()
               .join();
        for (int serverId = 1; serverId <= 3; serverId++) {
            final int id = serverId;
            await().ignoreExceptions().untilAsserted(() -> {
                assertThat(headRevisionOn(id).major()).isEqualTo(4);
                assertThat(jsonValueOn(id, "a")).isEqualTo(4);
            });
        }
        assertThat(getRepoStatus(adminClientOf(SOURCE_SERVER_ID), testProject, TEST_REPO).status())
                .isEqualTo(ReplicationStatus.WRITABLE);
    }

    private AggregatedHttpResponse recover(BlockingWebClient client, RecoverRepositoryRequest request) {
        return client.prepare()
                     .post("/api/v1/projects/{project}/repos/{repo}/recover")
                     .pathParam("project", testProject)
                     .pathParam("repo", TEST_REPO)
                     .contentJson(request)
                     .execute();
    }

    private Revision headRevisionOn(int serverId) {
        return replica.serverById(serverId).client()
                      .forRepo(testProject, TEST_REPO)
                      .normalize(Revision.HEAD)
                      .join();
    }

    private int jsonValueOn(int serverId, String field) {
        final Entry<JsonNode> entry = replica.serverById(serverId).client()
                                             .forRepo(testProject, TEST_REPO)
                                             .file(Query.ofJson("/a.json"))
                                             .get()
                                             .join();
        return entry.content().get(field).asInt();
    }

    private static BlockingWebClient adminClientOf(int serverId) {
        return replica.serverById(serverId).blockingHttpClient();
    }

    private static RepositoryDto getRepoStatus(BlockingWebClient client, String projectName,
                                               String repoName) {
        final ResponseEntity<RepositoryDto> response =
                client.prepare()
                      .get("/api/v1/projects/{project}/repos/{repo}")
                      .pathParam("project", projectName)
                      .pathParam("repo", repoName)
                      .asJson(RepositoryDto.class)
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        return response.content();
    }

    private static ServerStatus getServerStatus(BlockingWebClient client) {
        final ResponseEntity<ServerStatus> response =
                client.prepare()
                      .get("/api/v1/status")
                      .asJson(ServerStatus.class)
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        return response.content();
    }

    private static final class FaultInjector implements Plugin {

        private StandaloneCommandExecutor commandExecutor;
        private CommandExecutor zkCommandExecutor;

        /**
         * Originates the command through the fault-injected replica's replicated executor.
         */
        <T> T zkExecute(Command<T> command) {
            return zkCommandExecutor.execute(command).join();
        }

        /**
         * Applies the command directly on the fault-injected replica's local storage, bypassing the
         * replication log, so that replica diverges from the rest of the cluster.
         */
        void injectFault(Command<?> command) {
            final RepositoryCommand<?> repositoryCommand = (RepositoryCommand<?>) command;
            final String projectName = repositoryCommand.projectName();
            final String repoName = repositoryCommand.repositoryName();
            // Wait for the seed commit to be replayed; otherwise the fault fires one log entry too early.
            final Revision originHead =
                    client0.forRepo(projectName, repoName).normalize(Revision.HEAD).join();
            final CentralDogma injectorClient = replica.serverById(DIVERGED_SERVER_ID).client();
            await().untilAsserted(() -> {
                final Revision localHead =
                        injectorClient.forRepo(projectName, repoName).normalize(Revision.HEAD).join();
                assertThat(localHead.major()).isGreaterThanOrEqualTo(originHead.major());
            });
            commandExecutor.execute(command).join();
        }

        @Override
        public boolean isEnabled(CentralDogmaConfig config) {
            return true;
        }

        @Override
        public PluginTarget target(CentralDogmaConfig config) {
            return PluginTarget.ALL_REPLICAS;
        }

        @Override
        public CompletionStage<Void> start(PluginContext context) {
            zkCommandExecutor = context.commandExecutor();
            commandExecutor =
                    (StandaloneCommandExecutor) ((ZooKeeperCommandExecutor) zkCommandExecutor).unwrap();
            return UnmodifiableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> stop(PluginContext context) {
            return UnmodifiableFuture.completedFuture(null);
        }

        @Override
        public Class<?> configType() {
            return getClass();
        }
    }
}
