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

package com.linecorp.centraldogma.server.internal.api.sysadmin;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.management.ServerStatus;

public final class UpdateServerStatusRequest {
    private final ServerStatus serverStatus;
    private final Scope scope;

    @JsonCreator
    public UpdateServerStatusRequest(@JsonProperty("serverStatus") ServerStatus serverStatus,
                                     @JsonProperty("scope") @Nullable Scope scope) {
        this.serverStatus = requireNonNull(serverStatus, "serverStatus");
        this.scope = firstNonNull(scope, Scope.ALL);
    }

    @JsonProperty("serverStatus")
    public ServerStatus serverStatus() {
        return serverStatus;
    }

    @JsonProperty("scope")
    public Scope scope() {
        return scope;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UpdateServerStatusRequest)) {
            return false;
        }
        final UpdateServerStatusRequest that = (UpdateServerStatusRequest) o;
        return serverStatus == that.serverStatus && scope == that.scope;
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverStatus, scope);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("serverStatus", serverStatus)
                          .add("scope", scope)
                          .toString();
    }

    public enum Scope {
        LOCAL, ALL
    }
}
