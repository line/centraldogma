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

package com.linecorp.centraldogma.server.command;

import java.util.concurrent.CompletableFuture;

/**
 * An executor interface which executes {@link Command}s.
 */
public interface CommandExecutor {
    /**
     * Returns the ID of a replica.
     */
    int replicaId();

    /**
     * Returns {@code true} if the executor is started.
     */
    boolean isStarted();

    /**
     * Starts the executor.
     */
    CompletableFuture<Void> start();

    /**
     * Stops the executor.
     */
    CompletableFuture<Void> stop();

    /**
     * Returns {@code true} if the executor can accept the {@link Command}s making a change to the replica.
     * {@code false} would be returned if the replica is running as a read-only mode.
     */
    boolean isWritable();

    /**
     * Makes the executor read/write mode or read-only mode.
     *
     * @param writable {@code true} to make the executor read/write mode, or {@code false} to make it
     *                 read-only mode
     */
    void setWritable(boolean writable);

    /**
     * Executes the specified {@link Command}.
     *
     * @param command the command which is supposed to be executed
     * @param <T> the type of the result to be returned
     */
    default <T> CompletableFuture<T> execute(Command<T> command) {
        return execute(ExecutionContext.empty(), command);
    }

    /**
     * Executes the specified {@link Command} with the {@link ExecutionContext}.
     *
     * @param command the command which is supposed to be executed
     * @param ctx the execution context for this command
     * @param <T> the type of the result to be returned
     */
    <T> CompletableFuture<T> execute(ExecutionContext ctx, Command<T> command);

    /**
     * Returns the status manager of this executor.
     */
    CommandExecutorStatusManager statusManager();
}
