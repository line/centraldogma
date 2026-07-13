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

package com.linecorp.centraldogma.server;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.api.sysadmin.UpdateServerStatusRequest;
import com.linecorp.centraldogma.server.internal.api.sysadmin.UpdateServerStatusRequest.Scope;
import com.linecorp.centraldogma.server.internal.replication.ZooKeeperCommandExecutor;
import com.linecorp.centraldogma.server.management.ServerStatus;
import com.linecorp.centraldogma.testing.internal.CentralDogmaReplicationExtension;
import com.linecorp.centraldogma.testing.internal.CentralDogmaRuleDelegate;

class ReplicationOnlyRestartTest {

    @RegisterExtension
    final CentralDogmaReplicationExtension cluster = new CentralDogmaReplicationExtension(3) {
        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Test
    void restartInReplicationOnlyKeepsDelegateReadOnly() throws Exception {
        final BlockingWebClient client = cluster.servers().get(0).blockingHttpClient();
        final ResponseEntity<ServerStatus> response =
                client.prepare()
                      .put("/api/v1/status")
                      .contentJson(new UpdateServerStatusRequest(ServerStatus.REPLICATION_ONLY, Scope.ALL))
                      .asJson(ServerStatus.class)
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        // Wait until every replica has applied (and therefore persisted) REPLICATION_ONLY, so the restart
        // below reliably reads it back from server-status.properties on all replicas.
        await().untilAsserted(() -> {
            for (CentralDogmaRuleDelegate server : cluster.servers()) {
                assertThat(getServerStatus(server.blockingHttpClient()))
                        .isEqualTo(ServerStatus.REPLICATION_ONLY);
            }
        });

        cluster.stop();
        cluster.start();

        // The persisted REPLICATION_ONLY must reach the inner delegate at startup, not only the outer
        // ZooKeeperCommandExecutor; otherwise the delegate would come back writable.
        for (CentralDogmaRuleDelegate server : cluster.servers()) {
            final CommandExecutor executor = requireNonNull(server.dogma().executor());
            assertThat(executor).isInstanceOf(ZooKeeperCommandExecutor.class);
            assertThat(executor.isWritable()).isFalse();
            final CommandExecutor delegate = ((ZooKeeperCommandExecutor) executor).unwrap();
            assertThat(delegate.isWritable()).isFalse();
        }
    }

    private static ServerStatus getServerStatus(BlockingWebClient client) {
        return client.prepare()
                     .get("/api/v1/status")
                     .asJson(ServerStatus.class)
                     .execute()
                     .content();
    }
}
