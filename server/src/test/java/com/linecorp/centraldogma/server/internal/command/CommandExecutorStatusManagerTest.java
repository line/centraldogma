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

package com.linecorp.centraldogma.server.internal.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.centraldogma.server.QuotaConfig;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.command.CommandExecutorStatusManager;
import com.linecorp.centraldogma.server.command.UpdateServerStatusCommand;
import com.linecorp.centraldogma.server.management.ServerStatus;

class CommandExecutorStatusManagerTest {

    @Test
    void startExecutor() {
        final CommandExecutorStatusManager executorStatusManager =
                new CommandExecutorStatusManager(new TestCommandExecutor());
        final UpdateServerStatusCommand command =
                (UpdateServerStatusCommand) Command.updateServerStatus(ServerStatus.REPLICATION_ONLY);
        executorStatusManager.updateStatus(command);
        assertThat(executorStatusManager.writable()).isFalse();
        assertThat(executorStatusManager.replicating()).isTrue();
        executorStatusManager.setWritable(true);
        assertThat(executorStatusManager.writable()).isTrue();
        assertThat(executorStatusManager.replicating()).isTrue();
    }

    @Test
    void replicationOnlyToReadOnly() {
        final CommandExecutorStatusManager executorStatusManager =
                new CommandExecutorStatusManager(new TestCommandExecutor());
        executorStatusManager.setWritable(true);
        executorStatusManager.setReplicating(true);
        executorStatusManager.updateStatus(new UpdateServerStatusCommand(null, null,
                                                                         ServerStatus.REPLICATION_ONLY));
        assertThat(executorStatusManager.writable()).isFalse();
        assertThat(executorStatusManager.replicating()).isTrue();

        executorStatusManager.updateStatus(new UpdateServerStatusCommand(null, null, ServerStatus.READ_ONLY));
        assertThat(executorStatusManager.writable()).isFalse();
        assertThat(executorStatusManager.replicating()).isFalse();
    }

    private static class TestCommandExecutor implements CommandExecutor {

        private final CompletableFuture<Void> startFuture;
        private final CompletableFuture<Void> stopFuture;
        private boolean writable;
        private boolean started;

        TestCommandExecutor() {
            this(UnmodifiableFuture.completedFuture(null), UnmodifiableFuture.completedFuture(null));
        }

        TestCommandExecutor(CompletableFuture<Void> startFuture, CompletableFuture<Void> stopFuture) {
            this.startFuture = startFuture;
            this.stopFuture = stopFuture;
        }

        @Override
        public int replicaId() {
            return 0;
        }

        @Override
        public boolean isStarted() {
            return started;
        }

        @Override
        public CompletableFuture<Void> start() {
            return startFuture.thenAccept(unused -> started = true);
        }

        @Override
        public CompletableFuture<Void> stop() {
            return stopFuture.thenAccept(unused -> started = false);
        }

        @Override
        public boolean isWritable() {
            return writable;
        }

        @Override
        public void setWritable(boolean writable) {
            this.writable = writable;
        }

        @Override
        public void setWriteQuota(String projectName, String repoName, @Nullable QuotaConfig writeQuota) {
        }

        @Override
        public <T> CompletableFuture<T> execute(Command<T> command) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CommandExecutorStatusManager statusManager() {
            return new CommandExecutorStatusManager(this);
        }
    }
}
