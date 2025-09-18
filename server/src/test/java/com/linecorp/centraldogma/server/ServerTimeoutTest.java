/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.centraldogma.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchException;

import java.util.concurrent.CompletionException;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.RetryForever;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.CentralDogmaRepository;
import com.linecorp.centraldogma.common.ApiRequestTimeoutException;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.LockAcquireTimeoutException;
import com.linecorp.centraldogma.common.PushResult;
import com.linecorp.centraldogma.server.internal.replication.ZooKeeperCommandExecutor;
import com.linecorp.centraldogma.testing.internal.CentralDogmaReplicationExtension;
import com.linecorp.centraldogma.testing.internal.CentralDogmaRuleDelegate;

class ServerTimeoutTest {

    @RegisterExtension
    static final CentralDogmaReplicationExtension dogma = new CentralDogmaReplicationExtension(3) {
        @Override
        protected void configureEach(int serverId, CentralDogmaBuilder builder) {
            builder.requestTimeoutMillis(7000L);
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void testTimeoutException(boolean expectLockTimeout) throws Exception {
        final CentralDogmaRuleDelegate delegate = dogma.servers().get(0);
        final com.linecorp.centraldogma.server.CentralDogma server = delegate.dogma();
        if (expectLockTimeout) {
            // Set a shorter value than the request timeout.
            ((ZooKeeperCommandExecutor) server.executor()).setLockTimeoutMillis(3000L);
        } else {
            // Set a longer value than the request timeout.
            ((ZooKeeperCommandExecutor) server.executor()).setLockTimeoutMillis(15000L);
        }
        final CentralDogmaConfig config = server.config();

        final ZooKeeperReplicationConfig replicationConfig =
                (ZooKeeperReplicationConfig) config.replicationConfig();
        final int clientPort = replicationConfig.serverConfig().clientPort();

        // Because push isn't called yet, zookeeper_lock_acquired metric should not be present.
        String metrics = delegate.httpClient().get("/monitor/metrics").aggregate().join().contentUtf8();
        assertThat(metrics).doesNotContain("zookeeper_lock_acquired");

        final CuratorFramework curator = CuratorFrameworkFactory.newClient(
                "127.0.0.1:" + clientPort, new RetryForever(100));
        curator.start();
        final InterProcessMutex mutex = new InterProcessMutex(curator, "/dogma/lock/foo/bar");

        final CentralDogma client = delegate.client();
        client.createProject("foo").join();
        final CentralDogmaRepository barRepo = client.createRepository("foo", "bar").join();
        mutex.acquire();

        final Exception caughtException = catchException(() -> {
            barRepo.commit("Test", Change.ofTextUpsert("/a.txt", "hello"))
                   .push()
                   .join();
        });
        if (expectLockTimeout) {
            assertThat(caughtException)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(LockAcquireTimeoutException.class);
        } else {
            assertThat(caughtException)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ApiRequestTimeoutException.class)
                    .hasMessageContaining("Request timed out");
        }
        mutex.release();
        Thread.sleep(1000);

        // Make sure the server is still writable after LockAcquireTimeoutException
        final PushResult pushResult = barRepo.commit("Test", Change.ofTextUpsert("/a.txt", "world"))
                                             .push()
                                             .join();
        assertThat(pushResult.revision().major()).isPositive();

        // Because push is called, zookeeper_lock_acquired metric should be present.
        metrics = delegate.httpClient().get("/monitor/metrics").aggregate().join().contentUtf8();
        assertThat(metrics).contains("zookeeper_lock_acquired");
    }
}
