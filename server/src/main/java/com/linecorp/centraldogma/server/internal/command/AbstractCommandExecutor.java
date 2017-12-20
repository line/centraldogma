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
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import com.linecorp.centraldogma.server.internal.replication.ReplicationException;

public abstract class AbstractCommandExecutor implements CommandExecutor {

    private enum State {
        CREATED, STARTED, STOPPED
    }

    private final String replicaId;
    private final AtomicReference<State> state = new AtomicReference<>(State.CREATED);

    protected AbstractCommandExecutor(String replicaId) {
        this.replicaId = requireNonNull(replicaId, "replicaId");
    }

    @Override
    public final String replicaId() {
        return replicaId;
    }

    @Override
    public final void start(@Nullable Runnable onTakeLeadership, @Nullable Runnable onReleaseLeadership) {
        checkState(State.CREATED);

        boolean started = false;
        try {
            doStart(onTakeLeadership, onReleaseLeadership);
            enterState(State.CREATED, State.STARTED);
            started = true;
        } finally {
            if (!started) {
                doStop();
            }
        }
    }

    protected abstract void doStart(@Nullable Runnable onTakeLeadership,
                                    @Nullable Runnable onReleaseLeadership);

    @Override
    public final void stop() {
        if (state.getAndSet(State.STOPPED) == State.STARTED) {
            doStop();
        }
    }

    protected abstract void doStop();

    @Override
    public boolean isStarted() {
        return state.get() == State.STARTED;
    }

    @Override
    public final <T> CompletableFuture<T> execute(Command<T> command) {
        return execute(replicaId, command);
    }

    @Override
    public final <T> CompletableFuture<T> execute(String replicaId, Command<T> command) {
        requireNonNull(replicaId, "replicaId");
        requireNonNull(command, "command");
        if (!isStarted()) {
            throw new IllegalStateException("running in read-only mode");
        }

        try {
            return doExecute(replicaId, command);
        } catch (Throwable t) {
            final CompletableFuture<T> f = new CompletableFuture<>();
            f.completeExceptionally(t);
            return f;
        }
    }

    protected abstract <T> CompletableFuture<T> doExecute(
            String replicaId, Command<T> command) throws Exception;

    private void checkState(State expected) {
        if (state.get() != expected) {
            try {
                throw new ReplicationException("invalid state: " + state.get());
            } finally {
                stop();
            }
        }
    }

    private void enterState(State oldState, State newState) {
        if (!state.compareAndSet(oldState, newState)) {
            try {
                throw new ReplicationException("invalid state " + state.get());
            } finally {
                stop();
            }
        }
    }
}
