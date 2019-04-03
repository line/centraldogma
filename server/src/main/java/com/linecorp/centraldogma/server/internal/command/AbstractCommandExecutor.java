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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.StartStopSupport;

public abstract class AbstractCommandExecutor implements CommandExecutor {

    @Nullable
    private final Consumer<CommandExecutor> onTakeLeadership;
    @Nullable
    private final Runnable onReleaseLeadership;

    private final CommandExecutorStartStop startStop = new CommandExecutorStartStop();
    private volatile boolean started;
    private volatile boolean writable = true;
    private final AtomicInteger numPendingStopRequests = new AtomicInteger();

    protected AbstractCommandExecutor(@Nullable Consumer<CommandExecutor> onTakeLeadership,
                                      @Nullable Runnable onReleaseLeadership) {
        this.onTakeLeadership = onTakeLeadership;
        this.onReleaseLeadership = onReleaseLeadership;
    }

    @Override
    public final boolean isStarted() {
        return started;
    }

    protected final boolean isStopping() {
        return numPendingStopRequests.get() > 0;
    }

    @Override
    public final CompletableFuture<Void> start() {
        return startStop.start(false).thenRun(() -> started = true);
    }

    protected abstract void doStart(@Nullable Runnable onTakeLeadership,
                                    @Nullable Runnable onReleaseLeadership) throws Exception;

    @Override
    public final CompletableFuture<Void> stop() {
        started = false;
        numPendingStopRequests.incrementAndGet();
        return startStop.stop().thenRun(numPendingStopRequests::decrementAndGet);
    }

    protected abstract void doStop(@Nullable Runnable onReleaseLeadership) throws Exception;

    @Override
    public final boolean isWritable() {
        return isStarted() && writable;
    }

    @Override
    public final void setWritable(boolean writable) {
        this.writable = writable;
    }

    @Override
    public final <T> CompletableFuture<T> execute(Command<T> command) {
        requireNonNull(command, "command");
        if (!isWritable()) {
            throw new IllegalStateException("running in read-only mode");
        }

        try {
            return doExecute(command);
        } catch (Throwable t) {
            final CompletableFuture<T> f = new CompletableFuture<>();
            f.completeExceptionally(t);
            return f;
        }
    }

    protected abstract <T> CompletableFuture<T> doExecute(Command<T> command) throws Exception;

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("writable", isWritable())
                          .add("replicating", started)
                          .toString();
    }

    private final class CommandExecutorStartStop extends StartStopSupport<Void, Void, Void, Void> {

        CommandExecutorStartStop() {
            super(ForkJoinPool.commonPool());
        }

        @Override
        protected CompletionStage<Void> doStart(Void arg) throws Exception {
            return execute("command-executor", () -> {
                try {
                    final Runnable onTakeLeadership;
                    if (AbstractCommandExecutor.this.onTakeLeadership != null) {
                        onTakeLeadership = () -> {
                            AbstractCommandExecutor.this.onTakeLeadership.accept(AbstractCommandExecutor.this);
                        };
                    } else {
                        onTakeLeadership = null;
                    }

                    AbstractCommandExecutor.this.doStart(onTakeLeadership, onReleaseLeadership);
                } catch (Exception e) {
                    Exceptions.throwUnsafely(e);
                }
            });
        }

        @Override
        protected CompletionStage<Void> doStop(Void arg) throws Exception {
            return execute("command-executor-shutdown",
                           () -> {
                               try {
                                   AbstractCommandExecutor.this.doStop(onReleaseLeadership);
                               } catch (Exception e) {
                                   Exceptions.throwUnsafely(e);
                               }
                           });
        }

        private CompletionStage<Void> execute(String threadNamePrefix, Runnable task) {
            final CompletableFuture<Void> future = new CompletableFuture<>();
            final String threadName = threadNamePrefix + "-0x" +
                                      Long.toHexString(AbstractCommandExecutor.this.hashCode() & 0xFFFFFFFFL);
            final Thread thread = new Thread(() -> {
                try {
                    task.run();
                    future.complete(null);
                } catch (Throwable cause) {
                    future.completeExceptionally(cause);
                }
            }, threadName);
            thread.start();
            return future;
        }
    }
}
