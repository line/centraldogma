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

package com.linecorp.centraldogma.server.internal.command;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.linecorp.centraldogma.internal.Util;

public class ForwardingCommandExecutor implements CommandExecutor {

    private final CommandExecutor delegate;

    protected ForwardingCommandExecutor(CommandExecutor delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    protected <T extends CommandExecutor> T delegate() {
        return Util.unsafeCast(delegate);
    }

    @Override
    public String replicaId() {
        return delegate().replicaId();
    }

    @Override
    public void start(@Nullable Runnable onTakeLeadership, @Nullable Runnable onReleaseLeadership) {
        delegate().start(onTakeLeadership, onReleaseLeadership);
    }

    @Override
    public void stop() {
        delegate().stop();
    }

    @Override
    public boolean isStarted() {
        return delegate().isStarted();
    }

    @Override
    public <T> CompletableFuture<T> execute(Command<T> command) {
        return delegate().execute(command);
    }

    @Override
    public <T> CompletableFuture<T> execute(String replicaId, Command<T> command) {
        return delegate().execute(replicaId, command);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + '(' + delegate() + ')';
    }
}
