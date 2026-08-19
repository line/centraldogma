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

package com.linecorp.centraldogma.webapp;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME2;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.getAccessToken;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import org.apache.shiro.config.Ini;
import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Markup;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfig;
import com.linecorp.centraldogma.server.ZooKeeperServerConfig;
import com.linecorp.centraldogma.server.auth.shiro.ShiroAuthProviderFactory;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.StandaloneCommandExecutor;
import com.linecorp.centraldogma.server.internal.replication.ZooKeeperCommandExecutor;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;

/**
 * A {@link ShiroCentralDogmaTestServer} variant that runs three replicas in one JVM, on ports 36462 to
 * 36464. With {@code CD_DIVERGE} set, it also leaves {@code foo/diverged} diverged and read-only.
 */
final class ReplicatedShiroCentralDogmaTestServer {

    private static final String DIVERGE_ENV = "CD_DIVERGE";

    private static final int PORT1 = 36462;
    private static final int PORT2 = 36463;
    private static final int PORT3 = 36464;

    private static final Map<Integer, ZooKeeperServerConfig> SERVERS = ImmutableMap.of(
            1, new ZooKeeperServerConfig("127.0.0.1", 36466, 36467, 36468, null, null),
            2, new ZooKeeperServerConfig("127.0.0.1", 36469, 36470, 36471, null, null),
            3, new ZooKeeperServerConfig("127.0.0.1", 36472, 36473, 36474, null, null));

    /**
     * Starts the cluster and keeps it running until the JVM exits.
     */
    @SuppressWarnings("UncommentedMain")
    public static void main(String[] args) throws IOException {
        final CentralDogma server1 = newServer(1, PORT1);
        final CentralDogma server2 = newServer(2, PORT2, injector);
        final CentralDogma server3 = newServer(3, PORT3);
        // The quorum needs its peers, so start them concurrently.
        final var start1 = server1.start();
        final var start2 = server2.start();
        final var start3 = server3.start();
        start1.join();
        start2.join();
        start3.join();
        scaffold(System.getenv(DIVERGE_ENV) != null);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server1.close();
            server2.close();
            server3.close();
        }));
    }

    private static final DivergenceInjector injector = new DivergenceInjector();

    private static CentralDogma newServer(int serverId, int port, Plugin... plugins) throws IOException {
        final Path rootDir = Files.createTempDirectory("dogma-replicated-test-" + serverId);
        return new CentralDogmaBuilder(rootDir.toFile())
                .port(port, SessionProtocol.HTTP)
                .systemAdministrators(USERNAME)
                .cors("http://127.0.0.1:36462", "http://127.0.0.1:36463", "http://127.0.0.1:3000",
                      "http://localhost:36462", "http://localhost:36463", "http://localhost:3000")
                .authProviderFactory(new ShiroAuthProviderFactory(unused -> {
                    final Ini iniConfig = new Ini();
                    final Ini.Section users = iniConfig.addSection("users");
                    users.put(USERNAME, PASSWORD);
                    users.put(USERNAME2, PASSWORD2);
                    return iniConfig;
                }))
                .replication(new ZooKeeperReplicationConfig(
                        serverId, SERVERS, "test-secret-for-replication-0123456789abcdef"))
                .plugins(plugins)
                .build();
    }

    private static void scaffold(boolean diverge) throws UnknownHostException, JsonProcessingException {
        final String token = getAccessToken(WebClient.of("http://127.0.0.1:" + PORT1), USERNAME, PASSWORD,
                                            "appId", true);
        final com.linecorp.centraldogma.client.CentralDogma client = new ArmeriaCentralDogmaBuilder()
                .host("127.0.0.1", PORT1)
                .accessToken(token)
                .build();
        client.createProject("foo").join();
        client.createRepository("foo", "bar").join();
        client.forRepo("foo", "bar")
              .commit("add a.json", Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }"))
              .push()
              .join();
        client.forRepo("foo", "bar")
              .commit("update a.json", Change.ofJsonUpsert("/a.json", "{ \"a\": 2 }"))
              .push()
              .join();

        if (diverge) {
            diverge(client);
        }
    }

    /**
     * Leaves foo/diverged in the state a recovery repairs: replica 2 holds content the others never saw,
     * so its replay of the next push fails and the repository turns read-only cluster-wide.
     */
    private static void diverge(com.linecorp.centraldogma.client.CentralDogma client) {
        client.createRepository("foo", "diverged").join();
        client.forRepo("foo", "diverged")
              .commit("seed", Change.ofJsonUpsert("/a.json", "{ \"a\": 1 }"))
              .push()
              .join();

        // Applied on replica 2 alone, bypassing the replication log.
        injector.inject(Command.push(Author.DEFAULT, "foo", "diverged", Revision.HEAD,
                                     "diverged on replica 2", "", Markup.PLAINTEXT,
                                     ImmutableList.of(Change.ofJsonUpsert("/a.json", "{ \"a\": 3 }"))));

        // Replica 2 cannot replay this one on top of what it applied locally.
        client.forRepo("foo", "diverged")
              .commit("update on replica 1", Change.ofJsonUpsert("/a.json", "{ \"a\": 2 }"))
              .push()
              .join();
    }

    /**
     * Applies a command on this replica's local storage only, so it diverges from the rest of the cluster.
     */
    private static final class DivergenceInjector implements Plugin {

        @Nullable
        private volatile StandaloneCommandExecutor localExecutor;

        void inject(Command<?> command) {
            final StandaloneCommandExecutor executor = localExecutor;
            if (executor == null) {
                throw new IllegalStateException("the injector is not started yet");
            }
            executor.execute(command).join();
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
            localExecutor = (StandaloneCommandExecutor)
                    ((ZooKeeperCommandExecutor) context.commandExecutor()).unwrap();
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

    private ReplicatedShiroCentralDogmaTestServer() {}
}
