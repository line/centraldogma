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

package com.linecorp.centraldogma.server.auth;

import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.MALFORMED_SESSION_ID;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.USERNAME;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.WRONG_PASSWORD;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.WRONG_SESSION_ID;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.login;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.logout;
import static com.linecorp.centraldogma.testing.internal.auth.TestAuthMessageUtil.usersMe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryUntilElapsed;
import org.apache.curator.test.InstanceSpec;
import org.awaitility.Durations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.GracefulShutdownTimeout;
import com.linecorp.centraldogma.server.ZooKeeperAddress;
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfig;
import com.linecorp.centraldogma.testing.internal.TemporaryFolderExtension;
import com.linecorp.centraldogma.testing.internal.auth.TestAuthProviderFactory;

class ReplicatedLoginAndLogoutTest {

    @RegisterExtension
    static final TemporaryFolderExtension tempDir = new TemporaryFolderExtension();

    // Prepare two replicas.

    private CentralDogma replica1;
    private CentralDogma replica2;
    private WebClient client1;
    private WebClient client2;
    private CuratorFramework curator;

    @BeforeEach
    void setUp() throws Exception {
        final int port1 = InstanceSpec.getRandomPort();
        final int zkQuorumPort1 = InstanceSpec.getRandomPort();
        final int zkElectionPort1 = InstanceSpec.getRandomPort();
        final int zkClientPort1 = InstanceSpec.getRandomPort();

        final int port2 = InstanceSpec.getRandomPort();
        final int zkQuorumPort2 = InstanceSpec.getRandomPort();
        final int zkElectionPort2 = InstanceSpec.getRandomPort();
        final int zkClientPort2 = InstanceSpec.getRandomPort();

        final Map<Integer, ZooKeeperAddress> servers = ImmutableMap.of(
                1, new ZooKeeperAddress("127.0.0.1", zkQuorumPort1, zkElectionPort1, zkClientPort1),
                2, new ZooKeeperAddress("127.0.0.1", zkQuorumPort2, zkElectionPort2, zkClientPort2));

        final AuthProviderFactory factory = new TestAuthProviderFactory();

        replica1 = new CentralDogmaBuilder(tempDir.newFolder().toFile())
                .port(port1, SessionProtocol.HTTP)
                .authProviderFactory(factory)
                .webAppEnabled(true)
                .mirroringEnabled(false)
                .gracefulShutdownTimeout(new GracefulShutdownTimeout(0, 0))
                .replication(new ZooKeeperReplicationConfig(1, servers))
                .build();

        replica2 = new CentralDogmaBuilder(tempDir.newFolder().toFile())
                .port(port2, SessionProtocol.HTTP)
                .authProviderFactory(factory)
                .webAppEnabled(true)
                .mirroringEnabled(false)
                .gracefulShutdownTimeout(new GracefulShutdownTimeout(0, 0))
                .replication(new ZooKeeperReplicationConfig(2, servers))
                .build();

        client1 = WebClient.of("http://127.0.0.1:" + port1);
        client2 = WebClient.of("http://127.0.0.1:" + port2);

        final CompletableFuture<Void> f1 = replica1.start();
        final CompletableFuture<Void> f2 = replica2.start();

        f1.join();
        f2.join();

        curator = CuratorFrameworkFactory.newClient("127.0.0.1:" + zkClientPort1,
                                                    new RetryUntilElapsed(10000, 100));
        curator.start();
        assertThat(curator.blockUntilConnected(10, TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach
    void tearDown() {
        if (curator != null) {
            curator.close();
        }
        if (replica1 != null) {
            replica1.stop();
        }
        if (replica2 != null) {
            replica2.stop();
        }
    }

    @Test
    void loginAndLogout() throws Exception {
        final int baselineReplicationLogCount = replicationLogCount();

        // Log in from the 1st replica.
        final AggregatedHttpResponse loginRes = login(client1, USERNAME, PASSWORD);
        assertThat(loginRes.status()).isEqualTo(HttpStatus.OK);

        // Ensure that only one replication log is produced.
        assertThat(replicationLogCount()).isEqualTo(baselineReplicationLogCount + 1);

        // Ensure authorization works at the 2nd replica.
        final AccessToken accessToken = Jackson.readValue(loginRes.contentUtf8(), AccessToken.class);
        final String sessionId = accessToken.accessToken();
        await().pollDelay(Durations.TWO_HUNDRED_MILLISECONDS)
               .pollInterval(Durations.ONE_SECOND)
               .untilAsserted(() -> assertThat(usersMe(client2, sessionId).status()).isEqualTo(HttpStatus.OK));

        // Ensure that no replication log is produced.
        assertThat(replicationLogCount()).isEqualTo(baselineReplicationLogCount + 1);

        // Log out from the 1st replica.
        assertThat(logout(client1, sessionId).status()).isEqualTo(HttpStatus.OK);

        // Ensure that only one replication log is produced.
        assertThat(replicationLogCount()).isEqualTo(baselineReplicationLogCount + 2);

        // Ensure authorization fails at the 2nd replica.
        await().pollDelay(Durations.TWO_HUNDRED_MILLISECONDS)
               .pollInterval(Durations.ONE_SECOND)
               .untilAsserted(() -> assertThat(usersMe(client2, sessionId).status())
                       .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void incorrectLogin() throws Exception {
        final int baselineReplicationLogCount = replicationLogCount();
        assertThat(login(client1, USERNAME, WRONG_PASSWORD).status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Ensure that a failed login attempt does not produce any replication logs.
        assertThat(replicationLogCount()).isEqualTo(baselineReplicationLogCount);
    }

    @Test
    void incorrectLogout() throws Exception {
        final int baselineReplicationLogCount = replicationLogCount();
        assertThat(logout(client1, WRONG_SESSION_ID).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(logout(client1, MALFORMED_SESSION_ID).status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Ensure that a failed logout attempt does not produce any replication logs.
        assertThat(replicationLogCount()).isEqualTo(baselineReplicationLogCount);
    }

    private int replicationLogCount() throws Exception {
        assert curator != null;
        return curator.getChildren().forPath("/dogma/logs").size();
    }
}
