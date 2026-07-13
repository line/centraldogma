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

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.ReadOnlyException;
import com.linecorp.centraldogma.common.ReplicationStatus;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.jsonpatch.JsonPatchOperation;
import com.linecorp.centraldogma.internal.api.v1.RepositoryDto;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.RepositoryCommand;
import com.linecorp.centraldogma.server.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.api.sysadmin.UpdateServerStatusRequest;
import com.linecorp.centraldogma.server.internal.api.sysadmin.UpdateServerStatusRequest.Scope;
import com.linecorp.centraldogma.server.management.ServerStatus;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.testing.internal.CentralDogmaReplicationExtension;
import com.linecorp.centraldogma.testing.internal.CentralDogmaRuleDelegate;

class ZooKeeperScopedReadonlyIntegrationTest {

    private static final String TEST_REPO1 = "test-repo1";
    private static final String TEST_REPO2 = "test-repo2";
    private static final AtomicInteger testCounter = new AtomicInteger();
    private static final FaultInjector faultInjector = new FaultInjector();

    @RegisterExtension
    static final CentralDogmaReplicationExtension replica = new CentralDogmaReplicationExtension(3) {
        @Override
        protected void configureEach(int serverId, CentralDogmaBuilder builder) {
            if (serverId == 2) {
                builder.plugins(faultInjector);
            }
        }
    };

    private static CentralDogma client0;

    private String testProject1;
    private String testProject2;

    @BeforeEach
    void beforeEach() {
        client0 = replica.servers().get(0).client();

        // Restore the server status to WRITABLE so that a previous test that escalated to
        // REPLICATION_ONLY does not block project/repository creation below. The reset is replicated
        // (Scope.ALL) rather than local, because a replica that has not replayed the previous
        // REPLICATION_ONLY log entry yet would otherwise apply it after a local reset and turn read-only
        // in the middle of this test. A replicated reset is appended after that entry, so every replica
        // ends up WRITABLE.
        replica.servers().get(0).blockingHttpClient()
               .prepare()
               .put("/api/v1/status")
               .contentJson(new UpdateServerStatusRequest(ServerStatus.WRITABLE, Scope.ALL))
               .execute();
        for (int i = 0; i < 3; i++) {
            final BlockingWebClient adminClient = replica.servers().get(i).blockingHttpClient();
            await().untilAsserted(() -> {
                assertThat(getServerStatus(adminClient)).isEqualTo(ServerStatus.WRITABLE);
            });
        }

        final int index = testCounter.incrementAndGet();
        testProject1 = "test-project1-" + index;
        testProject2 = "test-project2-" + index;
        client0.createProject(testProject1).join();
        client0.createRepository(testProject1, TEST_REPO1).join();
        client0.createRepository(testProject1, TEST_REPO2).join();
        client0.createProject(testProject2).join();
        client0.createRepository(testProject2, TEST_REPO1).join();
        client0.createRepository(testProject2, TEST_REPO2).join();
    }

    @AfterEach
    void afterEach() {
        // All replicas must re-converge on the same dogma/dogma revision after every test.
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            final long head0 = dogmaRepoHeadRevision(0);
            final long head1 = dogmaRepoHeadRevision(1);
            final long head2 = dogmaRepoHeadRevision(2);
            assertThat(head1).isEqualTo(head0);
            assertThat(head2).isEqualTo(head0);
        });
    }

    private static long dogmaRepoHeadRevision(int serverIndex) {
        return replica.servers().get(serverIndex).client()
                      .forRepo(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA)
                      .normalize(Revision.HEAD)
                      .join()
                      .major();
    }

    @Test
    void repositoryReadonly() {
        final CentralDogmaRepository repo1 = client0.forRepo(testProject1, TEST_REPO1);
        repo1.commit("first", Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }"))
             .push()
             .join();

        final Change<JsonNode> unknownChange = Change.ofJsonUpsert("/a.json", "{ \"a\": 3 }");
        final Command<Revision> unknownCommand = Command.push(Author.DEFAULT, testProject1, TEST_REPO1,
                                                              Revision.HEAD, "inject fault", "",
                                                              Markup.PLAINTEXT,
                                                              ImmutableList.of(unknownChange));
        faultInjector.injectFault(unknownCommand);

        final Change<JsonNode> jsonPatch =
                Change.ofJsonPatch("/a.json",
                                   JsonPatchOperation.safeReplace("/a", new IntNode(1), new IntNode(2)));

        repo1.commit("second", jsonPatch)
             .push()
             .join();

        final BlockingWebClient client1 = replica.servers().get(1).blockingHttpClient();

        await().untilAsserted(() -> {
            final RepositoryDto repoDto = getRepoStatus(client1, testProject1, TEST_REPO1);
            assertThat(repoDto.status()).isEqualTo(ReplicationStatus.READ_ONLY);
        });

        // The scoped read-only should be propagated to other replicas.
        await().untilAsserted(() -> {
            for (int i = 0; i < 3; i++) {
                final BlockingWebClient webClient = replica.servers().get(0).blockingHttpClient();
                final RepositoryDto readonlyRepo = getRepoStatus(webClient, testProject1, TEST_REPO1);
                assertThat(readonlyRepo.status()).isEqualTo(ReplicationStatus.READ_ONLY);
            }
        });

        // Make sure that other repositories are still writable.
        RepositoryDto repoDto = getRepoStatus(client1, testProject1, TEST_REPO2);
        assertThat(repoDto.status()).isEqualTo(ReplicationStatus.WRITABLE);
        repoDto = getRepoStatus(client1, testProject1, "dogma");
        assertThat(repoDto.status()).isEqualTo(ReplicationStatus.WRITABLE);
        assertThat(getServerStatus(client1)).isEqualTo(ServerStatus.WRITABLE);
        repoDto = getRepoStatus(client1, testProject2, TEST_REPO1);
        assertThat(repoDto.status()).isEqualTo(ReplicationStatus.WRITABLE);
        repoDto = getRepoStatus(client1, testProject2, TEST_REPO2);
        assertThat(repoDto.status()).isEqualTo(ReplicationStatus.WRITABLE);

        // Pushing to the read-only repository should fail.
        assertThatThrownBy(() -> {
            repo1.commit("third", Change.ofJsonUpsert("/a.json", "{ \"a\": 4 }"))
                 .push()
                 .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(ReadOnlyException.class);

        // Other repositories should be writable.
        final CentralDogmaRepository repo2 = client0.forRepo(testProject1, TEST_REPO2);
        repo2.commit("third", Change.ofJsonUpsert("/a.json", "{ \"a\": 4 }"))
             .push()
             .join();
    }

    @Test
    void projectReadonly() {
        final String path = "/repos/test-project1/mirrors/a.json";
        final CentralDogmaRepository dogmaRepo = client0.forRepo(testProject1, "dogma");
        dogmaRepo.commit("first", Change.ofJsonUpsert(path, "{ \"a\": 1 }"))
                 .push()
                 .join();

        final Change<JsonNode> invalidChange = Change.ofJsonUpsert(path, "{ \"a\": 3 }");
        final Command<Revision> invalidCommand = Command.push(Author.DEFAULT, testProject1, "dogma",
                                                              Revision.HEAD, "inject fault", "",
                                                              Markup.PLAINTEXT,
                                                              ImmutableList.of(invalidChange));
        faultInjector.injectFault(invalidCommand);

        final Change<JsonNode> jsonPatch =
                Change.ofJsonPatch(path,
                                   JsonPatchOperation.safeReplace("/a", new IntNode(1), new IntNode(2)));

        dogmaRepo.commit("second", jsonPatch)
                 .push()
                 .join();

        final BlockingWebClient client1 = replica.servers().get(1).blockingHttpClient();

        await().untilAsserted(() -> {
            final RepositoryDto repoDto = getRepoStatus(client1, testProject1, "dogma");
            assertThat(repoDto.status()).isEqualTo(ReplicationStatus.READ_ONLY);
        });

        // The scoped read-only should be propagated to other replicas.
        await().untilAsserted(() -> {
            for (int i = 0; i < 3; i++) {
                final BlockingWebClient webClient = replica.servers().get(0).blockingHttpClient();
                final RepositoryDto readonlyRepo = getRepoStatus(webClient, testProject1, "dogma");
                assertThat(readonlyRepo.status()).isEqualTo(ReplicationStatus.READ_ONLY);
            }
        });

        // Make sure that all repositories in the test_project1 are readonly.
        RepositoryDto repoDto = getRepoStatus(client1, testProject1, TEST_REPO2);
        assertThat(repoDto.status()).isEqualTo(ReplicationStatus.READ_ONLY);
        repoDto = getRepoStatus(client1, testProject1, "dogma");
        assertThat(repoDto.status()).isEqualTo(ReplicationStatus.READ_ONLY);

        // The server should be writable.
        assertThat(getServerStatus(client1)).isEqualTo(ServerStatus.WRITABLE);

        // Other projects should be writable.
        repoDto = getRepoStatus(client1, testProject2, TEST_REPO1);
        assertThat(repoDto.status()).isEqualTo(ReplicationStatus.WRITABLE);
        repoDto = getRepoStatus(client1, testProject2, TEST_REPO2);
        assertThat(repoDto.status()).isEqualTo(ReplicationStatus.WRITABLE);
    }

    @Test
    void serverReadonly() {
        final String path = "/repos/dummy/mirrors/server-readonly.json";
        final CentralDogmaRepository internalDogmaRepo =
                client0.forRepo(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA,
                                Project.REPO_DOGMA);
        internalDogmaRepo.commit("first", Change.ofJsonUpsert(path, "{ \"a\": 1 }"))
                         .push()
                         .join();

        final Change<JsonNode> invalidChange = Change.ofJsonUpsert(path, "{ \"a\": 3 }");
        final Command<Revision> invalidCommand = Command.push(
                Author.DEFAULT,
                InternalProjectInitializer.INTERNAL_PROJECT_DOGMA, Project.REPO_DOGMA,
                Revision.HEAD, "inject fault", "",
                Markup.PLAINTEXT,
                ImmutableList.of(invalidChange));
        faultInjector.injectFault(invalidCommand);

        final Change<JsonNode> jsonPatch =
                Change.ofJsonPatch(path,
                                   JsonPatchOperation.safeReplace(
                                           "/a", new IntNode(1), new IntNode(2)));

        internalDogmaRepo.commit("second", jsonPatch)
                         .push()
                         .join();

        // The dogma/dogma fault should escalate to a server-wide REPLICATION_ONLY
        // and propagate to every replica including the leader.
        await().untilAsserted(() -> {
            for (int i = 0; i < 3; i++) {
                final BlockingWebClient webClient = replica.servers().get(i).blockingHttpClient();
                assertThat(getServerStatus(webClient)).isEqualTo(ServerStatus.REPLICATION_ONLY);
            }
        });

        // Every repository in every project should now report READ_ONLY.
        final BlockingWebClient client1 = replica.servers().get(1).blockingHttpClient();
        for (String projectName : ImmutableList.of(testProject1, testProject2)) {
            for (String repoName : ImmutableList.of(TEST_REPO1, TEST_REPO2, Project.REPO_DOGMA)) {
                final RepositoryDto repoDto = getRepoStatus(client1, projectName, repoName);
                assertThat(repoDto.status())
                        .as("%s/%s", projectName, repoName)
                        .isEqualTo(ReplicationStatus.READ_ONLY);
            }
        }

        // Pushes are blocked everywhere, including dogma/dogma — the per-repo
        // writability check exempts dogma/dogma, but the executor-level
        // !writable guard does not.
        final CentralDogmaRepository repo1 = client0.forRepo(testProject1, TEST_REPO1);
        assertThatThrownBy(() -> {
            repo1.commit("after-readonly",
                         Change.ofJsonUpsert("/after-readonly.json", "{ \"x\": 1 }"))
                 .push()
                 .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(ReadOnlyException.class);

        final CentralDogmaRepository project2Repo = client0.forRepo(testProject2, TEST_REPO1);
        assertThatThrownBy(() -> {
            project2Repo.commit("after-readonly",
                                Change.ofJsonUpsert("/after-readonly.json", "{ \"x\": 1 }"))
                        .push()
                        .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(ReadOnlyException.class);

        assertThatThrownBy(() -> {
            internalDogmaRepo.commit("after-readonly",
                                     Change.ofJsonUpsert(
                                             "/repos/dummy/mirrors/after-readonly.json",
                                             "{ \"a\": 1 }"))
                             .push()
                             .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(ReadOnlyException.class);

        // Reads must still succeed in REPLICATION_ONLY mode.
        final RepositoryDto readDto = getRepoStatus(client1, testProject1, TEST_REPO1);
        assertThat(readDto.name()).isEqualTo(TEST_REPO1);
    }

    @Test
    void replicationOnlyReplicaKeepsReplaying() {
        // Put replica 1 alone into REPLICATION_ONLY; the rest of the cluster stays writable.
        final BlockingWebClient webClient1 = replica.servers().get(1).blockingHttpClient();
        final ResponseEntity<ServerStatus> statusResponse =
                webClient1.prepare()
                          .put("/api/v1/status")
                          .contentJson(new UpdateServerStatusRequest(ServerStatus.REPLICATION_ONLY,
                                                                     Scope.LOCAL))
                          .asJson(ServerStatus.class)
                          .execute();
        assertThat(statusResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(getServerStatus(webClient1)).isEqualTo(ServerStatus.REPLICATION_ONLY);

        // The origin still accepts writes while replica 1 is read-only.
        final PushResult pushed = client0.forRepo(testProject1, TEST_REPO1)
                                         .commit("push-while-replica-readonly",
                                                 Change.ofJsonUpsert("/replicated.json", "{ \"a\": 1 }"))
                                         .push()
                                         .join();

        // The read-only replica must keep applying replayed commands instead of dropping them.
        final CentralDogmaRepository repo1OnReplica1 =
                replica.servers().get(1).client().forRepo(testProject1, TEST_REPO1);
        // ignoreExceptions: replica 1 may not have replayed the project creation yet.
        await().ignoreExceptions().untilAsserted(() -> {
            final Revision localHead = repo1OnReplica1.normalize(Revision.HEAD).join();
            assertThat(localHead.major()).isGreaterThanOrEqualTo(pushed.revision().major());
        });
        final Entry<JsonNode> entry = repo1OnReplica1.file(Query.ofJson("/replicated.json")).get().join();
        assertThat(entry.content().get("a").asInt()).isEqualTo(1);

        // No read-only escalation was replicated for the repo — the replay succeeded cleanly.
        final BlockingWebClient webClient0 = replica.servers().get(0).blockingHttpClient();
        assertThat(getRepoStatus(webClient0, testProject1, TEST_REPO1).status())
                .isEqualTo(ReplicationStatus.WRITABLE);

        // Replaying must not flip the replica back to writable.
        assertThat(getServerStatus(webClient1)).isEqualTo(ServerStatus.REPLICATION_ONLY);
    }

    @Test
    void replicationOnlyReplicaReplaysProjectCreation() {
        final BlockingWebClient webClient1 = replica.servers().get(1).blockingHttpClient();
        final ResponseEntity<ServerStatus> statusResponse =
                webClient1.prepare()
                          .put("/api/v1/status")
                          .contentJson(new UpdateServerStatusRequest(ServerStatus.REPLICATION_ONLY,
                                                                     Scope.LOCAL))
                          .asJson(ServerStatus.class)
                          .execute();
        assertThat(statusResponse.status()).isEqualTo(HttpStatus.OK);

        // A project creation is not a SystemAdministrativeCommand; it must still replay on the replica.
        final String lateProject = testProject1 + "-late";
        client0.createProject(lateProject).join();
        client0.createRepository(lateProject, TEST_REPO1).join();

        final CentralDogmaRepository lateRepoOnReplica1 =
                replica.servers().get(1).client().forRepo(lateProject, TEST_REPO1);
        await().ignoreExceptions().untilAsserted(() -> {
            final Revision localHead = lateRepoOnReplica1.normalize(Revision.HEAD).join();
            assertThat(localHead.major()).isGreaterThanOrEqualTo(Revision.INIT.major());
        });
        assertThat(getServerStatus(webClient1)).isEqualTo(ServerStatus.REPLICATION_ONLY);
    }

    @Test
    void replicationOnlyReplicaReplaysWhileSiblingRepoReadonly() {
        // serverId == 2 in configureEach() is the fault-injected replica.
        final CentralDogmaRuleDelegate faultReplica = replica.serverById(2);
        final BlockingWebClient faultReplicaAdmin = faultReplica.blockingHttpClient();

        // Drive TEST_REPO1 into cluster-wide repo-scope READ_ONLY, then also put the fault-injected replica
        // into LOCAL REPLICATION_ONLY, so both read-only gates are active on it at once.
        driveRepoIntoReadOnly(testProject1, TEST_REPO1);
        enterReplicationOnly(faultReplicaAdmin);

        // The origin pushes to the still-writable sibling repo.
        final PushResult pushed = client0.forRepo(testProject1, TEST_REPO2)
                                         .commit("push-to-writable-sibling",
                                                 Change.ofJsonUpsert("/sibling.json", "{ \"b\": 1 }"))
                                         .push()
                                         .join();

        // The replica must replay it despite the server-level REPLICATION_ONLY gate and the sibling
        // READ_ONLY repo — neither exemption may drop the log entry.
        final CentralDogmaRepository repo2OnReplica = faultReplica.client().forRepo(testProject1, TEST_REPO2);
        await().ignoreExceptions().untilAsserted(() -> {
            final Revision localHead = repo2OnReplica.normalize(Revision.HEAD).join();
            assertThat(localHead.major()).isGreaterThanOrEqualTo(pushed.revision().major());
        });
        final Entry<JsonNode> entry = repo2OnReplica.file(Query.ofJson("/sibling.json")).get().join();
        assertThat(entry.content().get("b").asInt()).isEqualTo(1);

        // Neither gate flipped. Repo scopes are asserted on a writable replica, because a locally
        // read-only replica composes its server status into every per-repo status.
        final BlockingWebClient webClient0 = replica.servers().get(0).blockingHttpClient();
        assertThat(getRepoStatus(webClient0, testProject1, TEST_REPO1).status())
                .isEqualTo(ReplicationStatus.READ_ONLY);
        assertThat(getRepoStatus(webClient0, testProject1, TEST_REPO2).status())
                .isEqualTo(ReplicationStatus.WRITABLE);
        assertThat(getServerStatus(faultReplicaAdmin)).isEqualTo(ServerStatus.REPLICATION_ONLY);
    }

    @Test
    void serverReadonlySupersedesRepositoryReadonly() {
        final CentralDogmaRepository victimRepo = client0.forRepo(testProject1, TEST_REPO1);
        victimRepo.commit("seed-victim", Change.ofJsonUpsert("/v.json", "{ \"v\": 1 }"))
                  .push()
                  .join();

        faultInjector.injectFault(Command.push(
                Author.DEFAULT, testProject1, TEST_REPO1, Revision.HEAD,
                "inject fault (repo)", "", Markup.PLAINTEXT,
                ImmutableList.of(Change.ofJsonUpsert("/v.json", "{ \"v\": 3 }"))));

        // Drive testProject1/TEST_REPO1 into repo-scope read-only first.
        victimRepo.commit("victim-divergence",
                          Change.ofJsonPatch("/v.json",
                                             JsonPatchOperation.safeReplace(
                                                     "/v", new IntNode(1), new IntNode(2))))
                  .push()
                  .join();

        final BlockingWebClient webClient1 = replica.servers().get(1).blockingHttpClient();
        await().untilAsserted(() -> assertThat(
                getRepoStatus(webClient1, testProject1, TEST_REPO1).status())
                .isEqualTo(ReplicationStatus.READ_ONLY));

        // The server itself and sibling repos are still writable.
        assertThat(getServerStatus(webClient1)).isEqualTo(ServerStatus.WRITABLE);
        assertThat(getRepoStatus(webClient1, testProject1, TEST_REPO2).status())
                .isEqualTo(ReplicationStatus.WRITABLE);
        assertThat(getRepoStatus(webClient1, testProject2, TEST_REPO1).status())
                .isEqualTo(ReplicationStatus.WRITABLE);

        final BlockingWebClient webClient0 = replica.servers().get(0).blockingHttpClient();
        assertThat(getServerStatus(webClient0)).isEqualTo(ServerStatus.WRITABLE);
        // Escalate by triggering a server-scope failure on dogma/dogma.
        final String dogmaPath = "/repos/dummy/mirrors/server-readonly-2.json";
        final CentralDogmaRepository internalDogmaRepo =
                client0.forRepo(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA,
                                Project.REPO_DOGMA);
        internalDogmaRepo.commit("seed-server",
                                 Change.ofJsonUpsert(dogmaPath, "{ \"a\": 1 }"))
                         .push()
                         .join();

        faultInjector.injectFault(Command.push(
                Author.DEFAULT, InternalProjectInitializer.INTERNAL_PROJECT_DOGMA,
                Project.REPO_DOGMA, Revision.HEAD,
                "inject fault (server)", "", Markup.PLAINTEXT,
                ImmutableList.of(Change.ofJsonUpsert(dogmaPath, "{ \"a\": 3 }"))));

        internalDogmaRepo.commit("server-divergence",
                                 Change.ofJsonPatch(
                                         dogmaPath,
                                         JsonPatchOperation.safeReplace(
                                                 "/a", new IntNode(1), new IntNode(2))))
                         .push()
                         .join();

        await().untilAsserted(() -> {
            for (int i = 0; i < 3; i++) {
                final BlockingWebClient webClient = replica.servers().get(i).blockingHttpClient();
                assertThat(getServerStatus(webClient)).isEqualTo(ServerStatus.REPLICATION_ONLY);
            }
        });

        // Server scope wins: previously-writable siblings now report READ_ONLY too.
        assertThat(getRepoStatus(webClient1, testProject1, TEST_REPO2).status())
                .isEqualTo(ReplicationStatus.READ_ONLY);
        assertThat(getRepoStatus(webClient1, testProject2, TEST_REPO1).status())
                .isEqualTo(ReplicationStatus.READ_ONLY);
        assertThat(getRepoStatus(webClient1, testProject1, TEST_REPO1).status())
                .isEqualTo(ReplicationStatus.READ_ONLY);
    }

    @Test
    void serverReadonlyBlocksProjectAndRepositoryCreation() {
        final String path = "/repos/dummy/mirrors/server-readonly-3.json";
        final CentralDogmaRepository internalDogmaRepo =
                client0.forRepo(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA,
                                Project.REPO_DOGMA);
        internalDogmaRepo.commit("first", Change.ofJsonUpsert(path, "{ \"a\": 1 }"))
                         .push()
                         .join();

        faultInjector.injectFault(Command.push(
                Author.DEFAULT, InternalProjectInitializer.INTERNAL_PROJECT_DOGMA,
                Project.REPO_DOGMA, Revision.HEAD,
                "inject fault", "", Markup.PLAINTEXT,
                ImmutableList.of(Change.ofJsonUpsert(path, "{ \"a\": 3 }"))));

        internalDogmaRepo.commit("second",
                                 Change.ofJsonPatch(
                                         path,
                                         JsonPatchOperation.safeReplace(
                                                 "/a", new IntNode(1), new IntNode(2))))
                         .push()
                         .join();

        await().untilAsserted(() -> {
            for (int i = 0; i < 3; i++) {
                final BlockingWebClient webClient = replica.servers().get(i).blockingHttpClient();
                assertThat(getServerStatus(webClient)).isEqualTo(ServerStatus.REPLICATION_ONLY);
            }
        });

        // RootCommand and ProjectCommand are not SystemAdministrativeCommand, so the
        // executor-level !writable guard rejects them.
        assertThatThrownBy(() -> client0.createProject("new-project-after-readonly").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ReadOnlyException.class);

        assertThatThrownBy(() -> client0.createRepository(testProject1, "new-repo").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ReadOnlyException.class);
    }

    @Test
    void forcePushBypassesRepositoryReadonly() {
        final CentralDogmaRepository repo1 = client0.forRepo(testProject1, TEST_REPO1);
        repo1.commit("first", Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }"))
             .push()
             .join();

        faultInjector.injectFault(Command.push(
                Author.DEFAULT, testProject1, TEST_REPO1, Revision.HEAD,
                "inject fault", "", Markup.PLAINTEXT,
                ImmutableList.of(Change.ofJsonUpsert("/a.json", "{ \"a\": 3 }"))));

        repo1.commit("second",
                     Change.ofJsonPatch("/a.json",
                                        JsonPatchOperation.safeReplace(
                                                "/a", new IntNode(1), new IntNode(2))))
             .push()
             .join();

        final BlockingWebClient client1 = replica.servers().get(1).blockingHttpClient();
        await().untilAsserted(() -> assertThat(
                getRepoStatus(client1, testProject1, TEST_REPO1).status())
                .isEqualTo(ReplicationStatus.READ_ONLY));

        // A regular push is rejected.
        assertThatThrownBy(() -> {
            repo1.commit("blocked", Change.ofJsonUpsert("/blocked.json", "{}"))
                 .push()
                 .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(ReadOnlyException.class);

        // Force-push bypasses the per-repo writability check.
        final Revision rev = faultInjector.forcePush(Command.push(
                Author.SYSTEM, testProject1, TEST_REPO1, Revision.HEAD,
                "force-push", "", Markup.PLAINTEXT,
                ImmutableList.of(Change.ofJsonUpsert("/forced.json", "{ \"forced\": true }"))));
        assertThat(rev).isNotNull();

        // The repository is still marked READ_ONLY — force-push didn't lift the scope.
        assertThat(getRepoStatus(client1, testProject1, TEST_REPO1).status())
                .isEqualTo(ReplicationStatus.READ_ONLY);

        // Sibling repos remain writable.
        client0.forRepo(testProject1, TEST_REPO2)
               .commit("sibling-after-force-push",
                       Change.ofJsonUpsert("/sibling.json", "{}"))
               .push()
               .join();
    }

    @Test
    void forcePushBypassesProjectReadonly() {
        final String path = "/repos/test-project1/mirrors/force.json";
        final CentralDogmaRepository dogmaRepo = client0.forRepo(testProject1, "dogma");
        dogmaRepo.commit("first", Change.ofJsonUpsert(path, "{ \"a\": 1 }"))
                 .push()
                 .join();

        faultInjector.injectFault(Command.push(
                Author.DEFAULT, testProject1, "dogma", Revision.HEAD,
                "inject fault", "", Markup.PLAINTEXT,
                ImmutableList.of(Change.ofJsonUpsert(path, "{ \"a\": 3 }"))));

        dogmaRepo.commit("second",
                         Change.ofJsonPatch(
                                 path,
                                 JsonPatchOperation.safeReplace(
                                         "/a", new IntNode(1), new IntNode(2))))
                 .push()
                 .join();

        final BlockingWebClient client1 = replica.servers().get(1).blockingHttpClient();
        await().untilAsserted(() -> assertThat(
                getRepoStatus(client1, testProject1, TEST_REPO1).status())
                .isEqualTo(ReplicationStatus.READ_ONLY));

        // A regular push to any repo in the read-only project is rejected.
        final CentralDogmaRepository repo1 = client0.forRepo(testProject1, TEST_REPO1);
        assertThatThrownBy(() -> {
            repo1.commit("blocked", Change.ofJsonUpsert("/blocked.json", "{}"))
                 .push()
                 .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(ReadOnlyException.class);

        // Force-push bypasses the project-scope check.
        final Revision rev = faultInjector.forcePush(Command.push(
                Author.SYSTEM, testProject1, TEST_REPO1, Revision.HEAD,
                "force-push", "", Markup.PLAINTEXT,
                ImmutableList.of(Change.ofJsonUpsert("/forced.json", "{ \"forced\": true }"))));
        assertThat(rev).isNotNull();

        // Other projects remain writable as before.
        client0.forRepo(testProject2, TEST_REPO1)
               .commit("other-project-after-force-push",
                       Change.ofJsonUpsert("/other.json", "{}"))
               .push()
               .join();
    }

    @Test
    void forcePushBypassesServerReadonly() {
        final String path = "/repos/dummy/mirrors/force-server.json";
        final CentralDogmaRepository internalDogmaRepo =
                client0.forRepo(InternalProjectInitializer.INTERNAL_PROJECT_DOGMA,
                                Project.REPO_DOGMA);
        internalDogmaRepo.commit("first", Change.ofJsonUpsert(path, "{ \"a\": 1 }"))
                         .push()
                         .join();

        faultInjector.injectFault(Command.push(
                Author.DEFAULT, InternalProjectInitializer.INTERNAL_PROJECT_DOGMA,
                Project.REPO_DOGMA, Revision.HEAD,
                "inject fault", "", Markup.PLAINTEXT,
                ImmutableList.of(Change.ofJsonUpsert(path, "{ \"a\": 3 }"))));

        internalDogmaRepo.commit("second",
                                 Change.ofJsonPatch(
                                         path,
                                         JsonPatchOperation.safeReplace(
                                                 "/a", new IntNode(1), new IntNode(2))))
                         .push()
                         .join();

        await().untilAsserted(() -> {
            for (int i = 0; i < 3; i++) {
                final BlockingWebClient webClient = replica.servers().get(i).blockingHttpClient();
                assertThat(getServerStatus(webClient)).isEqualTo(ServerStatus.REPLICATION_ONLY);
            }
        });

        // A regular push is rejected by the executor-level !writable guard.
        final CentralDogmaRepository repo1 = client0.forRepo(testProject1, TEST_REPO1);
        assertThatThrownBy(() -> {
            repo1.commit("blocked", Change.ofJsonUpsert("/blocked.json", "{}"))
                 .push()
                 .join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(ReadOnlyException.class);

        // Force-push is a SystemAdministrativeCommand and bypasses the guard.
        final Revision rev = faultInjector.forcePush(Command.push(
                Author.SYSTEM, testProject1, TEST_REPO1, Revision.HEAD,
                "force-push", "", Markup.PLAINTEXT,
                ImmutableList.of(Change.ofJsonUpsert("/forced.json", "{ \"forced\": true }"))));
        assertThat(rev).isNotNull();

        // Server status is unchanged — force-push didn't undo the REPLICATION_ONLY.
        final BlockingWebClient client1 = replica.servers().get(1).blockingHttpClient();
        assertThat(getServerStatus(client1)).isEqualTo(ServerStatus.REPLICATION_ONLY);
    }

    private static void enterReplicationOnly(BlockingWebClient admin) {
        final ResponseEntity<ServerStatus> statusResponse =
                admin.prepare()
                     .put("/api/v1/status")
                     .contentJson(new UpdateServerStatusRequest(ServerStatus.REPLICATION_ONLY, Scope.LOCAL))
                     .asJson(ServerStatus.class)
                     .execute();
        assertThat(statusResponse.status()).isEqualTo(HttpStatus.OK);
        await().untilAsserted(
                () -> assertThat(getServerStatus(admin)).isEqualTo(ServerStatus.REPLICATION_ONLY));
    }

    private static void driveRepoIntoReadOnly(String projectName, String repoName) {
        final CentralDogmaRepository repo = client0.forRepo(projectName, repoName);
        repo.commit("seed", Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }")).push().join();
        faultInjector.injectFault(Command.push(
                Author.DEFAULT, projectName, repoName, Revision.HEAD,
                "inject fault", "", Markup.PLAINTEXT,
                ImmutableList.of(Change.ofJsonUpsert("/a.json", "{ \"a\": 3 }"))));
        repo.commit("divergence",
                    Change.ofJsonPatch("/a.json",
                                       JsonPatchOperation.safeReplace("/a", new IntNode(1), new IntNode(2))))
            .push()
            .join();
        // Confirm the repo-scope escalation on a writable replica; a locally read-only replica would
        // compose its server status into every per-repo status.
        final BlockingWebClient writableReplica = replica.servers().get(0).blockingHttpClient();
        await().untilAsserted(() -> assertThat(
                getRepoStatus(writableReplica, projectName, repoName).status())
                .isEqualTo(ReplicationStatus.READ_ONLY));
    }

    private static RepositoryDto getRepoStatus(BlockingWebClient client, String projectName, String repoName) {
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

        void injectFault(Command<?> command) {
            final RepositoryCommand<?> repositoryCommand = (RepositoryCommand<?>) command;
            final String projectName = repositoryCommand.projectName();
            final String repoName = repositoryCommand.repositoryName();
            // Wait for the seed commit to be replayed; otherwise the fault fires one log entry too early.
            final Revision originHead =
                    client0.forRepo(projectName, repoName).normalize(Revision.HEAD).join();
            final CentralDogma injectorClient = replica.servers().get(1).client();
            await().untilAsserted(() -> {
                final Revision localHead =
                        injectorClient.forRepo(projectName, repoName).normalize(Revision.HEAD).join();
                assertThat(localHead.major()).isGreaterThanOrEqualTo(originHead.major());
            });
            commandExecutor.execute(command).join();
        }

        <T> T forcePush(Command<T> delegate) {
            return zkCommandExecutor.execute(Command.forcePush(delegate)).join();
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
            final ZooKeeperCommandExecutor commandExecutor = (ZooKeeperCommandExecutor) zkCommandExecutor;
            this.commandExecutor = (StandaloneCommandExecutor) commandExecutor.unwrap();
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
