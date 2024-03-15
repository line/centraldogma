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

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.management.ServerStatusManager;

/**
 * Manages the status of a {@link CommandExecutor}.
 */
public final class CommandExecutorStatusManager {

    private static final Logger logger = LoggerFactory.getLogger(ServerStatusManager.class);

    private final CommandExecutor executor;

    private volatile ReplicatingRequest replicatingRequest = ReplicatingRequest.NONE;

    /**
     * Creates a new instance.
     */
    public CommandExecutorStatusManager(CommandExecutor executor) {
        this.executor = executor;
    }

    /**
     * Returns the executor that this {@link CommandExecutorStatusManager} manages.
     */
    public CommandExecutor executor() {
        return executor;
    }

    /**
     * Returns whether the {@link #executor()} is writable.
     */
    public boolean writable() {
        return executor.isWritable();
    }

    /**
     * Returns whether the {@link #executor()} is replicating.
     */
    public boolean replicating() {
        return executor.isStarted();
    }

    /**
     * Updates the status of the executor with the specified {@link UpdateServerStatusCommand}.
     *
     * <p>This method could take a long time if the executor is not in the desired state yet.
     * So it should be not called from an event loop thread.
     */
    public void updateStatus(UpdateServerStatusCommand command) {
        final Boolean writable = command.writable();
        if (writable != null) {
            setWritable(writable);
        }
        final Boolean replicating = command.replicating();
        if (replicating != null) {
            setReplicating(replicating);
        }
    }

    /**
     * Sets the executor to read/write mode or read-only mode.
     * @return {@code true} if the executor is already in the specified mode, or the mode has been updated
     *          successfully.
     */
    public boolean setWritable(boolean newWritable) {
        final boolean writable = writable();
        if (writable == newWritable) {
            return true;
        }
        executor.setWritable(newWritable);
        if (newWritable) {
            logger.warn("Left read-only mode.");
        } else {
            logger.warn("Entered read-only mode. replication: {}", replicating());
        }
        return true;
    }

    /**
     * Sets the executor to replicating mode or non-replicating mode.
     *
     * <p>This method could take a long time if the executor is not in the desired state yet.
     * So it should be not called from an event loop thread.
     *
     * @return {@code true} if the executor is already in the specified mode, or the mode has been updated
     *         successfully.
     */
    public boolean setReplicating(boolean newReplicating) {
        if (newReplicating) {
            try {
                startExecutor();
                logger.info("Enabled replication. read-only: {}", !writable());
                return true;
            } catch (Exception cause) {
                logger.warn("Failed to start the command executor:", cause);
                return false;
            }
        } else {
            try {
                stopExecutor();
                logger.info("Disabled replication");
                return true;
            } catch (Exception cause) {
                logger.warn("Failed to stop the command executor:", cause);
                return false;
            }
        }
    }

    /**
     * Starts the executor if the {@link CommandExecutor} is not started yet.
     */
    private void startExecutor() throws Exception {
        final CompletableFuture<Void> startFuture;
        synchronized (this) {
            switch (replicatingRequest) {
                case START:
                    // Start request has already been issued.
                    return;
                case STOP:
                    replicatingRequest = ReplicatingRequest.START;
                    logger.debug("Cancelled the previous replication stop request");
                    break;
                case NONE:
                    if (replicating()) {
                        return;
                    }
                    replicatingRequest = ReplicatingRequest.START;
                    break;
            }
            startFuture = executor.start();
        }

        try {
            for (;;) {
                if (replicatingRequest != ReplicatingRequest.START) {
                    // Stop waiting for the startFuture if a new stop request has been issued.
                    throw new CancellationException("'executor.start()' request has been cancelled");
                }

                try {
                    startFuture.get(100, TimeUnit.MILLISECONDS);
                    return;
                } catch (TimeoutException unused) {
                    // Taking long time ..
                }
            }
        } finally {
            replicatingRequest = ReplicatingRequest.NONE;
        }
    }

    /**
     * Stops the executor if the {@link CommandExecutor} is started.
     */
    private void stopExecutor() throws Exception {
        final CompletableFuture<Void> stopFuture;
        synchronized (this) {
            switch (replicatingRequest) {
                case STOP:
                    // Stop request has already been issued.
                    return;
                case START:
                    replicatingRequest = ReplicatingRequest.STOP;
                    logger.debug("Cancelled the previous replication start request");
                    break;
                case NONE:
                    if (!replicating()) {
                        return;
                    }
                    replicatingRequest = ReplicatingRequest.STOP;
                    break;
            }
            stopFuture = executor.stop();
        }

        try {
            for (;;) {
                if (replicatingRequest != ReplicatingRequest.STOP) {
                    // Stop waiting for the stopFuture if a new start request has been issued.
                    throw new CancellationException("'executor.stop()' request has been cancelled");
                }

                try {
                    stopFuture.get(100, TimeUnit.MILLISECONDS);
                    return;
                } catch (TimeoutException unused) {
                    // Taking long time ..
                }
            }
        } finally {
            replicatingRequest = ReplicatingRequest.NONE;
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("replicationRequest", replicatingRequest)
                          .add("isWritable", writable())
                          .add("replicating", replicating())
                          .toString();
    }

    enum ReplicatingRequest {
        START, STOP, NONE
    }
}
