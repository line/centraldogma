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

package com.linecorp.centraldogma.server.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.centraldogma.common.ReadOnlyException;
import com.linecorp.centraldogma.server.internal.api.sysadmin.UpdateServerStatusRequest;
import com.linecorp.centraldogma.server.internal.api.sysadmin.UpdateServerStatusRequest.Scope;
import com.linecorp.centraldogma.testing.internal.CentralDogmaReplicationExtension;
import com.linecorp.centraldogma.testing.internal.CentralDogmaRuleDelegate;

class ServerStatusManagerIntegrationTest {
    @RegisterExtension
    final CentralDogmaReplicationExtension cluster = new CentralDogmaReplicationExtension(3) {
        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Test
    void replicationOnlyMode() throws Exception {
        final CentralDogmaRuleDelegate dogma = cluster.servers().get(0);
        final BlockingWebClient client = dogma.httpClient().blocking();
        ReplicationStatus serverStatus = getServerStatus(client);

        // The initial status of the server.
        assertThat(serverStatus.writable()).isTrue();
        assertThat(serverStatus.replicating()).isTrue();

        // Read-only mode.
        serverStatus = updateServerStatus(client, ReplicationStatus.REPLICATION_ONLY);
        assertThat(serverStatus.writable()).isFalse();
        assertThat(serverStatus.replicating()).isTrue();
        assertAllServerStatus(false, true);
        assertThatThrownBy(() -> dogma.client().createProject("test-project").join())
                .hasCauseInstanceOf(ReadOnlyException.class);

        enableWritable();
        assertAllServerStatus(true, true);
        // Make sure that the server is writable again.
        dogma.client().createProject("test-project").join();
    }

    private void enableWritable() throws Exception {
        final List<CentralDogmaRuleDelegate> servers = cluster.servers();
        for (CentralDogmaRuleDelegate server : servers) {
            final ReplicationStatus serverStatus =
                    updateServerStatus(server.blockingHttpClient(), ReplicationStatus.WRITABLE, Scope.LOCAL);
            assertThat(serverStatus.writable()).isTrue();
            assertThat(serverStatus.replicating()).isTrue();
        }
    }

    @Test
    void preserveStatusAfterRestarting() throws Exception {
        final CentralDogmaRuleDelegate dogma = cluster.servers().get(0);
        dogma.client().createProject("test-project").join();

        final BlockingWebClient client = dogma.httpClient().blocking();
        // Read-only mode.
        ReplicationStatus serverStatus = updateServerStatus(client, ReplicationStatus.REPLICATION_ONLY);
        assertThat(serverStatus.writable()).isFalse();
        assertThat(serverStatus.replicating()).isTrue();
        assertAllServerStatus(false, true);

        cluster.stop();

        // Make sure that all servers are stopped.
        assertThatThrownBy(() -> getServerStatus(client))
                .isInstanceOf(UnprocessedRequestException.class)
                .hasCauseInstanceOf(ConnectException.class);

        // Restart the cluster with the same configuration.
        cluster.start();

        serverStatus = getServerStatus(client);
        assertThat(serverStatus.writable()).isFalse();
        assertThat(serverStatus.replicating()).isTrue();

        // Enable the writable mode.
        serverStatus = updateServerStatus(client, ReplicationStatus.WRITABLE, Scope.LOCAL);
        assertThat(serverStatus.writable()).isTrue();
        assertThat(serverStatus.replicating()).isTrue();

        // Disable both the writable and replicating mode.
        serverStatus = updateServerStatus(client, ReplicationStatus.READ_ONLY);
        assertThat(serverStatus.writable()).isFalse();
        assertThat(serverStatus.replicating()).isFalse();
        assertAllServerStatus(false, false);

        cluster.stop();
        // Make sure that all servers are stopped.
        assertThatThrownBy(() -> getServerStatus(client))
                .isInstanceOf(UnprocessedRequestException.class)
                .hasCauseInstanceOf(ConnectException.class);

        cluster.start();
        serverStatus = getServerStatus(client);
        assertThat(serverStatus.writable()).isFalse();
        assertThat(serverStatus.replicating()).isFalse();

        // Make sure that read operations are still available.
        assertThat(dogma.client().listProjects().join())
                .containsExactlyInAnyOrder("dogma", "test-project");
        assertThatThrownBy(() -> dogma.client().createProject("test2-project").join())
                .hasCauseInstanceOf(ReadOnlyException.class);
    }

    @Test
    void updateSingleInstance() throws Exception {
        final CentralDogmaRuleDelegate dogma = cluster.servers().get(0);
        final BlockingWebClient client = dogma.httpClient().blocking();

        ReplicationStatus serverStatus = getServerStatus(client);
        assertThat(serverStatus.writable()).isTrue();
        assertThat(serverStatus.replicating()).isTrue();

        updateServerStatus(client, ReplicationStatus.READ_ONLY, Scope.LOCAL);

        serverStatus = getServerStatus(client);
        assertThat(serverStatus.writable()).isFalse();
        assertThat(serverStatus.replicating()).isFalse();

        for (int i = 1; i < cluster.servers().size(); i++) {
            serverStatus = getServerStatus(cluster.servers().get(i).httpClient().blocking());
            assertThat(serverStatus.writable()).isTrue();
            assertThat(serverStatus.replicating()).isTrue();
        }

        updateServerStatus(client, ReplicationStatus.WRITABLE, Scope.LOCAL);
        assertAllServerStatus(true, true);
    }

    private void assertAllServerStatus(boolean writable, boolean replicating) {
        for (CentralDogmaRuleDelegate server : cluster.servers()) {
            final BlockingWebClient otherClient = server.httpClient().blocking();
            final ReplicationStatus serverStatus = getServerStatus(otherClient);
            assertThat(serverStatus.writable()).isEqualTo(writable);
            assertThat(serverStatus.replicating()).isEqualTo(replicating);
        }
    }

    private static ReplicationStatus updateServerStatus(BlockingWebClient client,
                                                        ReplicationStatus serverStatus) throws Exception {
        return updateServerStatus(client, serverStatus, Scope.ALL);
    }

    private static ReplicationStatus updateServerStatus(BlockingWebClient client,
                                                        ReplicationStatus serverStatus, Scope scope)
            throws Exception {
        final ReplicationStatus newServerStatus =
                client.prepare()
                      .put("/api/v1/status")
                      .contentJson(new UpdateServerStatusRequest(serverStatus, scope))
                      .asJson(ReplicationStatus.class)
                      .execute()
                      .content();
        if (scope == Scope.LOCAL) {
            // Wait for the status to be replicated to the other servers.
            Thread.sleep(500);
        }
        return newServerStatus;
    }

    private static ReplicationStatus getServerStatus(BlockingWebClient client) {
        return client.prepare()
                     .get("/api/v1/status")
                     .asJson(ReplicationStatus.class)
                     .execute()
                     .content();
    }
}
