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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.management.ReplicationStatus;
import com.linecorp.centraldogma.server.management.ServerStatusManager;

/**
 * Manages the status of a {@link CommandExecutor}.
 */
public final class CommandExecutorStatusManager {

    private static final Logger logger = LoggerFactory.getLogger(ServerStatusManager.class);

    private final CommandExecutor executor;

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
    public synchronized void updateStatus(UpdateServerStatusCommand command) {
        final ReplicationStatus serverStatus = command.serverStatus();
        updateStatus(serverStatus);
    }

    /**
     * Updates the status of the executor with the specified {@link ReplicationStatus}.
     *
     * <p>This method could take a long time if the executor is not in the desired state yet.
     * So it should be not called from an event loop thread.
     */
    public synchronized void updateStatus(ReplicationStatus serverStatus) {
        if (serverStatus.replicating()) {
            // Replicating mode is enabled first to write data to the cluster.
            setReplicating(serverStatus.replicating());
            setWritable(serverStatus.writable());
        } else {
            // For graceful transition, writable mode is disabled first.
            setWritable(serverStatus.writable());
            setReplicating(serverStatus.replicating());
        }
    }

    /**
     * Sets the executor to read/write mode or read-only mode.
     * @return {@code true} if the executor is already in the specified mode, or the mode has been updated
     *          successfully.
     */
    public synchronized boolean setWritable(boolean newWritable) {
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
    public synchronized boolean setReplicating(boolean newReplicating) {
        if (newReplicating) {
            if (replicating()) {
                return true;
            }
            try {
                logger.info("Enabling replication ...");
                executor.start().get();
                logger.info("Enabled replication. read-only: {}", !writable());
                return true;
            } catch (Exception cause) {
                logger.warn("Failed to start the command executor:", cause);
                return false;
            }
        } else {
            if (!replicating()) {
                return true;
            }
            try {
                logger.info("Disabling replication ...");
                executor.stop().get();
                logger.info("Disabled replication");
                return true;
            } catch (Exception cause) {
                logger.warn("Failed to stop the command executor:", cause);
                return false;
            }
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("isWritable", writable())
                          .add("replicating", replicating())
                          .toString();
    }
}
