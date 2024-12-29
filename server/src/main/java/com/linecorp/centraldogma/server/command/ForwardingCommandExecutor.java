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

package com.linecorp.centraldogma.server.command;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.QuotaConfigSpec;

/**
 * A {@link CommandExecutor} which forwards all its method calls to another {@link CommandExecutor}.
 */
public class ForwardingCommandExecutor implements CommandExecutor {

    private final CommandExecutor delegate;

    protected ForwardingCommandExecutor(CommandExecutor delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    protected final <T extends CommandExecutor> T delegate() {
        return Util.unsafeCast(delegate);
    }

    @Override
    public int replicaId() {
        return delegate().replicaId();
    }

    @Override
    public boolean isStarted() {
        return delegate().isStarted();
    }

    @Override
    public CompletableFuture<Void> start() {
        return delegate().start();
    }

    @Override
    public CompletableFuture<Void> stop() {
        return delegate().stop();
    }

    @Override
    public boolean isWritable() {
        return delegate().isWritable();
    }

    @Override
    public void setWritable(boolean writable) {
        delegate().setWritable(writable);
    }

    @Override
    public void setWriteQuota(String projectName, String repoName, QuotaConfigSpec writeQuota) {
        delegate().setWriteQuota(projectName, repoName, writeQuota);
    }

    @Override
    public <T> CompletableFuture<T> execute(Command<T> command) {
        return delegate().execute(command);
    }

    @Override
    public CommandExecutorStatusManager statusManager() {
        return delegate().statusManager();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + '(' + delegate() + ')';
    }
}
