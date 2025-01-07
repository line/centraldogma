/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.centraldogma.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.curator.test.InstanceSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.armeria.ArmeriaCentralDogmaBuilder;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.GracefulShutdownTimeout;
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfig;
import com.linecorp.centraldogma.server.ZooKeeperServerConfig;
import com.linecorp.centraldogma.server.ZooKeeperServerConfigSpec;
import com.linecorp.centraldogma.server.auth.AuthProviderFactory;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.testing.internal.FlakyTest;
import com.linecorp.centraldogma.testing.internal.TemporaryFolderExtension;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;

@FlakyTest
class ReplicationWriteQuotaTest extends WriteQuotaTestBase {

    private static final AuthProviderFactory factory = new TestAuthProviderFactory();

    @RegisterExtension
    static final TemporaryFolderExtension tempDir = new TemporaryFolderExtension();

    private WebClient webClient;
    private CentralDogma dogmaClient;

    @Override
    protected WebClient webClient() {
        return webClient;
    }

    @Override
    protected CentralDogma dogmaClient() {
        return dogmaClient;
    }

    @BeforeEach
    void setUp() throws IOException {
        final int port1 = InstanceSpec.getRandomPort();
        final int port2 = InstanceSpec.getRandomPort();
        final int port3 = InstanceSpec.getRandomPort();

        final Map<Integer, ZooKeeperServerConfigSpec> servers = randomServerConfigs(3);

        final CompletableFuture<Void> r1 = startNewReplicaWithRetries(port1, 1, servers);
        final CompletableFuture<Void> r2 = startNewReplicaWithRetries(port2, 2, servers);
        final CompletableFuture<Void> r3 = startNewReplicaWithRetries(port3, 3, servers);
        r1.join();
        r2.join();
        r3.join();

        final String adminSessionId =
                getSessionId(port1, TestAuthMessageUtil.USERNAME, TestAuthMessageUtil.PASSWORD);

        webClient = WebClient.builder("http://127.0.0.1:" + port1)
                             .auth(AuthToken.ofOAuth2(adminSessionId))
                             .build();
        dogmaClient = new ArmeriaCentralDogmaBuilder()
                .accessToken(adminSessionId)
                .host(webClient.uri().getHost(), webClient.uri().getPort())
                .build();
    }

    private static Map<Integer, ZooKeeperServerConfigSpec> randomServerConfigs(int numReplicas) {
        final ImmutableMap.Builder<Integer, ZooKeeperServerConfigSpec> builder =
                ImmutableMap.builderWithExpectedSize(numReplicas);
        for (int i = 0; i < numReplicas; i++) {
            final int zkQuorumPort = InstanceSpec.getRandomPort();
            final int zkElectionPort = InstanceSpec.getRandomPort();
            final int zkClientPort = InstanceSpec.getRandomPort();

            builder.put(i + 1, new ZooKeeperServerConfig("127.0.0.1", zkQuorumPort, zkElectionPort,
                                                         zkClientPort, /* groupId */ null, /* weight */ 1));
        }
        return builder.build();
    }

    private static CompletableFuture<Void> startNewReplicaWithRetries(
            int port, int serverId, Map<Integer, ZooKeeperServerConfigSpec> servers) throws IOException {
        final AtomicReference<CompletableFuture<Void>> futureRef = new AtomicReference<>();
        await().pollInSameThread().pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            assertThatCode(() -> {
                futureRef.set(startNewReplica(port, serverId, servers));
            }).doesNotThrowAnyException();
        });
        return futureRef.get();
    }

    private static CompletableFuture<Void> startNewReplica(
            int port, int serverId, Map<Integer, ZooKeeperServerConfigSpec> servers) throws IOException {
        return new CentralDogmaBuilder(tempDir.newFolder().toFile())
                .port(port, SessionProtocol.HTTP)
                .systemAdministrators(TestAuthMessageUtil.USERNAME)
                .authProviderFactory(factory)
                .pluginConfigs(new MirroringServicePluginConfig(false))
                .writeQuotaPerRepository(5, 1)
                .gracefulShutdownTimeout(new GracefulShutdownTimeout(0, 0))
                .replication(new ZooKeeperReplicationConfig(serverId, servers))
                .build().start();
    }

    private static String getSessionId(int port1, String username, String password)
            throws JsonProcessingException {
        final WebClient client = WebClient.of("http://127.0.0.1:" + port1);
        final AggregatedHttpResponse response =
                TestAuthMessageUtil.login(client, username, password);

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        return Jackson.readValue(response.content().array(), AccessToken.class).accessToken();
    }
}
