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

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The status of the server, expressed as a combination of the {@code writable} and {@code replicating}
 * properties.
 */
public enum ServerStatus {

    /**
     * The server rejects writes and stops replaying the replication log. The replica no longer follows the
     * cluster, so it must be re-synced manually before it can safely rejoin.
     */
    READ_ONLY(false, false),
    /**
     * The server rejects client writes but keeps replaying the replication log, so the replica stays in
     * sync with the cluster while serving reads.
     */
    REPLICATION_ONLY(false, true),
    /**
     * The server accepts writes and replays the replication log. This is the normal operating status.
     */
    WRITABLE(true, true);

    private final boolean writable;
    private final boolean replicating;

    // TODO(trustin): Add more properties, e.g. method, host name, isLeader and config.

    ServerStatus(boolean writable, boolean replicating) {
        this.writable = writable;
        this.replicating = replicating;
    }

    /**
     * Returns the {@link ServerStatus} instance with the specified properties.
     */
    public static ServerStatus of(boolean writable, boolean replicating) {
        if (writable) {
            if (replicating) {
                return WRITABLE;
            } else {
                throw new IllegalArgumentException("replicating must be true if writable is true");
            }
        } else {
            if (replicating) {
                return REPLICATION_ONLY;
            } else {
                return READ_ONLY;
            }
        }
    }

    /**
     * Returns whether the server is writable.
     */
    @JsonProperty("writable")
    public boolean writable() {
        return writable;
    }

    /**
     * Returns whether the server is replicating.
     */
    @JsonProperty("replicating")
    public boolean replicating() {
        return replicating;
    }
}
