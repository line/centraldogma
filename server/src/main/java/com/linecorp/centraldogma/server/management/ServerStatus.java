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

import static com.google.common.base.MoreObjects.firstNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * The status of the server.
 */
@JsonInclude(Include.NON_NULL)
public final class ServerStatus {
    private final boolean writable;
    private final boolean replicating;

    // TODO(trustin): Add more properties, e.g. method, host name, isLeader and config.

    /**
     * Creates a new instance with the specified properties.
     */
    @JsonCreator
    public ServerStatus(@JsonProperty("writable") @Nullable Boolean writable,
                        @JsonProperty("replicating") @Nullable Boolean replicating) {
        writable = firstNonNull(writable, true);
        replicating = firstNonNull(replicating, true);
        if (writable && !replicating) {
            throw new IllegalArgumentException("replicating must be true if writable is true");
        }
        this.writable = writable;
        this.replicating = replicating;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServerStatus)) {
            return false;
        }

        final ServerStatus that = (ServerStatus) o;

        if (!Objects.equals(writable, that.writable)) {
            return false;
        }
        return Objects.equals(replicating, that.replicating);
    }

    @Override
    public int hashCode() {
        return Objects.hash(writable, replicating);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("writable", writable)
                          .add("replicating", replicating)
                          .toString();
    }
}
