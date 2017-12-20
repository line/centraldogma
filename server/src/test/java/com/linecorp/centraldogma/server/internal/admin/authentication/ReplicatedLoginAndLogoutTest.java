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

package com.linecorp.centraldogma.server.internal.admin.authentication;

import static com.linecorp.centraldogma.server.internal.admin.authentication.LoginAndLogoutTest.PASSWORD;
import static com.linecorp.centraldogma.server.internal.admin.authentication.LoginAndLogoutTest.USERNAME;
import static com.linecorp.centraldogma.server.internal.admin.authentication.LoginAndLogoutTest.WRONG_PASSWORD;
import static com.linecorp.centraldogma.server.internal.admin.authentication.LoginAndLogoutTest.WRONG_SESSION_ID;
import static com.linecorp.centraldogma.server.internal.admin.authentication.LoginAndLogoutTest.login;
import static com.linecorp.centraldogma.server.internal.admin.authentication.LoginAndLogoutTest.logout;
import static com.linecorp.centraldogma.server.internal.admin.authentication.LoginAndLogoutTest.newSecurityConfig;
import static com.linecorp.centraldogma.server.internal.admin.authentication.LoginAndLogoutTest.usersMe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryUntilElapsed;
import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingServer;
import org.awaitility.Duration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfig;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class ReplicatedLoginAndLogoutTest {

    @ClassRule
    public static final TemporaryFolder zkDataDir = new TemporaryFolder();

    private static TestingServer zkServer;
    private static CuratorFramework curator;

    @BeforeClass
    public static void startZooKeeper() throws Exception {
        zkServer = new TestingServer(new InstanceSpec(
                zkDataDir.newFolder(), -1, -1, -1, true, -1, -1, -1), true);
        curator = CuratorFrameworkFactory.newClient(zkServer.getConnectString(),
                                                    new RetryUntilElapsed(10000, 100));
        curator.start();
    }

    @AfterClass
    public static void stopZooKeeper() throws Exception {
        if (curator != null) {
            curator.close();
        }
        if (zkServer != null) {
            zkServer.close();
        }
    }

    // Prepare two replicas.

    @Rule
    public final CentralDogmaRule replica1 = new ReplicatedCentralDogmaRule();

    @Rule
    public final CentralDogmaRule replica2 = new ReplicatedCentralDogmaRule();

    @Test
    public void loginAndLogout() throws Exception {
        final int baselineReplicationLogCount = replicationLogCount();

        // Log in from the 1st replica.
        final AggregatedHttpMessage loginRes = login(replica1, USERNAME, PASSWORD);
        assertThat(loginRes.status()).isEqualTo(HttpStatus.OK);

        // Ensure that only one replication log is produced.
        assertThat(replicationLogCount()).isEqualTo(baselineReplicationLogCount + 1);

        // Ensure authorization works at the 2nd replica.
        final String sessionId = loginRes.content().toStringAscii();
        await().pollDelay(Duration.TWO_HUNDRED_MILLISECONDS)
               .pollInterval(Duration.ONE_SECOND)
               .untilAsserted(() -> assertThat(usersMe(replica2, sessionId).status()).isEqualTo(HttpStatus.OK));

        // Ensure that no replication log is produced.
        assertThat(replicationLogCount()).isEqualTo(baselineReplicationLogCount + 1);

        // Log out from the 1st replica.
        assertThat(logout(replica1, sessionId).status()).isEqualTo(HttpStatus.OK);

        // Ensure that only one replication log is produced.
        assertThat(replicationLogCount()).isEqualTo(baselineReplicationLogCount + 2);

        // Ensure authorization fails at the 2nd replica.
        await().pollDelay(Duration.TWO_HUNDRED_MILLISECONDS)
               .pollInterval(Duration.ONE_SECOND)
               .untilAsserted(() -> assertThat(usersMe(replica2, sessionId).status())
                       .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    public void incorrectLogin() throws Exception {
        final int baselineReplicationLogCount = replicationLogCount();
        assertThat(login(replica1, USERNAME, WRONG_PASSWORD).status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Ensure that a failed login attempt does not produce any replication logs.
        assertThat(replicationLogCount()).isEqualTo(baselineReplicationLogCount);
    }

    @Test
    public void incorrectLogout() throws Exception {
        final int baselineReplicationLogCount = replicationLogCount();
        assertThat(logout(replica1, WRONG_SESSION_ID).status()).isEqualTo(HttpStatus.OK);

        // Ensure that a failed logout attempt does not produce any replication logs.
        assertThat(replicationLogCount()).isEqualTo(baselineReplicationLogCount);
    }

    private static int replicationLogCount() throws Exception {
        return curator.getChildren().forPath("/dogma/logs").size();
    }

    private static final class ReplicatedCentralDogmaRule extends CentralDogmaRule {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.securityConfig(newSecurityConfig());
            builder.webAppEnabled(true);
            builder.replication(new ZooKeeperReplicationConfig(zkServer.getConnectString(), "/dogma"));
        }
    }
}
