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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.centraldogma.common.ReadOnlyException;
import com.linecorp.centraldogma.server.internal.api.UpdateServerStatusRequest;
import com.linecorp.centraldogma.server.internal.api.UpdateServerStatusRequest.Scope;
import com.linecorp.centraldogma.testing.internal.CentralDogmaReplicationExtension;
import com.linecorp.centraldogma.testing.internal.CentralDogmaRuleDelegate;
import com.linecorp.centraldogma.testing.internal.FlakyTest;

@FlakyTest
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
        ServerStatus serverStatus = getServerStatus(client);

        // The initial status of the server.
        assertThat(serverStatus.writable()).isTrue();
        assertThat(serverStatus.replicating()).isTrue();

        // Read-only mode.
        serverStatus = updateServerStatus(client, ServerStatus.REPLICATION_ONLY);
        assertThat(serverStatus.writable()).isFalse();
        assertThat(serverStatus.replicating()).isTrue();
        assertAllServerStatus(false, true);
        assertThatThrownBy(() -> dogma.client().createProject("test-project").join())
                .hasCauseInstanceOf(ReadOnlyException.class);

        serverStatus = updateServerStatus(client, ServerStatus.WRITABLE);
        assertThat(serverStatus.writable()).isTrue();
        assertThat(serverStatus.replicating()).isTrue();
        assertAllServerStatus(true, true);
        // Make sure that the server is writable again.
        dogma.client().createProject("test-project").join();
    }

    @Test
    void preserveStatusAfterRestarting() throws Exception {
        final CentralDogmaRuleDelegate dogma = cluster.servers().get(0);
        dogma.client().createProject("test-project").join();

        final BlockingWebClient client = dogma.httpClient().blocking();
        // Read-only mode.
        ServerStatus serverStatus = updateServerStatus(client, ServerStatus.REPLICATION_ONLY);
        assertThat(serverStatus.writable()).isFalse();
        assertThat(serverStatus.replicating()).isTrue();
        assertAllServerStatus(false, true);

        cluster.stop();

        // Make sure that all servers are stopped.
        assertThatThrownBy(() -> getServerStatus(client))
                .isInstanceOf(UnprocessedRequestException.class)
                .hasCauseInstanceOf(ConnectException.class);
        // Wait for the ports acquired to be released.
        Thread.sleep(5000);

        // Restart the cluster with the same configuration.
        cluster.start();

        serverStatus = getServerStatus(client);
        assertThat(serverStatus.writable()).isFalse();
        assertThat(serverStatus.replicating()).isTrue();

        // Enable the writable mode.
        serverStatus = updateServerStatus(client, ServerStatus.WRITABLE);
        assertThat(serverStatus.writable()).isTrue();
        assertThat(serverStatus.replicating()).isTrue();

        // Disable both the writable and replicating mode.
        serverStatus = updateServerStatus(client, ServerStatus.READ_ONLY);
        assertThat(serverStatus.writable()).isFalse();
        assertThat(serverStatus.replicating()).isFalse();
        assertAllServerStatus(false, false);

        cluster.stop();
        // Make sure that all servers are stopped.
        assertThatThrownBy(() -> getServerStatus(client))
                .isInstanceOf(UnprocessedRequestException.class)
                .hasCauseInstanceOf(ConnectException.class);
        // Wait for the ports acquired to be released.
        Thread.sleep(5000);

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

        ServerStatus serverStatus = getServerStatus(client);
        assertThat(serverStatus.writable()).isTrue();
        assertThat(serverStatus.replicating()).isTrue();

        updateServerStatus(client, ServerStatus.READ_ONLY, Scope.LOCAL);

        serverStatus = getServerStatus(client);
        assertThat(serverStatus.writable()).isFalse();
        assertThat(serverStatus.replicating()).isFalse();

        for (int i = 1; i < cluster.servers().size(); i++) {
            serverStatus = getServerStatus(cluster.servers().get(i).httpClient().blocking());
            assertThat(serverStatus.writable()).isTrue();
            assertThat(serverStatus.replicating()).isTrue();
        }

        updateServerStatus(client, ServerStatus.WRITABLE, Scope.LOCAL);
        assertAllServerStatus(true, true);
    }

    private void assertAllServerStatus(boolean writable, boolean replicating) {
        for (CentralDogmaRuleDelegate server : cluster.servers()) {
            final BlockingWebClient otherClient = server.httpClient().blocking();
            final ServerStatus serverStatus = getServerStatus(otherClient);
            assertThat(serverStatus.writable()).isEqualTo(writable);
            assertThat(serverStatus.replicating()).isEqualTo(replicating);
        }
    }

    private static ServerStatus updateServerStatus(BlockingWebClient client,
                                                   ServerStatus serverStatus) throws Exception {
        return updateServerStatus(client, serverStatus, Scope.ALL);
    }

    private static ServerStatus updateServerStatus(BlockingWebClient client,
                                                   ServerStatus serverStatus, Scope scope)
            throws Exception {
        final ServerStatus newServerStatus =
                client.prepare()
                      .put("/api/v1/status")
                      .contentJson(new UpdateServerStatusRequest(serverStatus, scope))
                      .asJson(ServerStatus.class)
                      .execute()
                      .content();
        // Wait for the status to be replicated to the other servers.
        Thread.sleep(500);
        return newServerStatus;
    }

    private static ServerStatus getServerStatus(BlockingWebClient client) {
        return client.prepare()
                     .get("/api/v1/status")
                     .asJson(ServerStatus.class)
                     .execute()
                     .content();
    }

    private static String patchServerStatus(boolean writable, boolean replicating) {
        final String writablePatch =
                "{ \"op\": \"replace\", \"path\": \"/writable\", \"value\": " + writable + " }";
        final String replicatingPatch =
                "{ \"op\": \"replace\", \"path\": \"/replicating\", \"value\": " + replicating + " }";
        return '[' + writablePatch + ", " + replicatingPatch + ']';
    }
}
