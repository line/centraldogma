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

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.centraldogma.testing.internal.TemporaryFolderExtension;

class ServerStatusManagerTest {

    @RegisterExtension
    TemporaryFolderExtension tmpDir = new TemporaryFolderExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @Test
    void defaultValues() throws IOException {
        final ServerStatusManager serverStatusManager = new ServerStatusManager(tmpDir.newFolder().toFile());
        final ReplicationStatus serverStatus = serverStatusManager.serverStatus();
        assertThat(serverStatus.writable()).isTrue();
        assertThat(serverStatus.replicating()).isTrue();
    }

    @Test
    void updateValues() throws IOException {
        final ServerStatusManager serverStatusManager = new ServerStatusManager(tmpDir.newFolder().toFile());
        serverStatusManager.updateStatus(ReplicationStatus.READ_ONLY);
        final ReplicationStatus serverStatus = serverStatusManager.serverStatus();
        assertThat(serverStatus.writable()).isFalse();
        assertThat(serverStatus.replicating()).isFalse();
    }

    @Test
    void readOnlyToReplicationOnly() throws IOException {
        final ServerStatusManager serverStatusManager = new ServerStatusManager(tmpDir.newFolder().toFile());
        serverStatusManager.updateStatus(ReplicationStatus.READ_ONLY);
        ReplicationStatus serverStatus = serverStatusManager.serverStatus();
        assertThat(serverStatus.writable()).isFalse();
        assertThat(serverStatus.replicating()).isFalse();

        serverStatusManager.updateStatus(ReplicationStatus.REPLICATION_ONLY);
        serverStatus = serverStatusManager.serverStatus();
        assertThat(serverStatus.writable()).isFalse();
        assertThat(serverStatus.replicating()).isTrue();
    }
}
