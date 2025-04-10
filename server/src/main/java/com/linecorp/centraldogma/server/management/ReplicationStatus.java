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
 * The replication status of the server or a repository.
 */
public enum ReplicationStatus {

    READ_ONLY(false, false),
    REPLICATION_ONLY(false, true),
    WRITABLE(true, true);

    private final boolean writable;
    private final boolean replicating;

    // TODO(trustin): Add more properties, e.g. method, host name, isLeader and config.

    ReplicationStatus(boolean writable, boolean replicating) {
        this.writable = writable;
        this.replicating = replicating;
    }

    /**
     * Returns the {@link ReplicationStatus} instance with the specified properties.
     */
    public static ReplicationStatus of(boolean writable, boolean replicating) {
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
