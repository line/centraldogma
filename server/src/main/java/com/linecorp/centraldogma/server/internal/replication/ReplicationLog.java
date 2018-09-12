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

package com.linecorp.centraldogma.server.internal.replication;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.Util;
import com.linecorp.centraldogma.server.internal.command.Command;

public final class ReplicationLog<T> {

    private final int replicaId;
    private final Command<T> command;
    private final T result;

    @JsonCreator
    static <T> ReplicationLog<T> deserialize(
            @JsonProperty("replicaId") @Nullable Integer replicaId,
            @JsonProperty("command") Command<T> command,
            @JsonProperty("result") JsonNode result) throws JsonProcessingException {
        return new ReplicationLog<>(requireNonNull(replicaId, "replicaId"),
                                    command, deserializeResult(result, command));
    }

    @Nullable
    private static <T> T deserializeResult(
            JsonNode result, Command<T> command) throws JsonProcessingException {

        requireNonNull(command, "command");

        // Convert null node to the real null.
        if (result != null && result.isNull()) {
            result = null;
        }

        final Class<T> resultType = Util.unsafeCast(command.type().resultType());
        if (resultType == Void.class) {
            if (result != null) {
                rejectIncompatibleResult(result, Void.class);
            }

            return null;
        }

        assert result != null;
        return Jackson.treeToValue(result, resultType);
    }

    ReplicationLog(int replicaId, Command<T> command, @Nullable T result) {

        this.replicaId = replicaId;
        this.command = requireNonNull(command, "command");

        final Class<?> resultType = command.type().resultType();
        if (resultType == Void.class) {
            if (result != null) {
                rejectIncompatibleResult(result, Void.class);
            }
        } else {
            requireNonNull(result, "result");
            if (!resultType.isInstance(result)) {
                rejectIncompatibleResult(result, resultType);
            }
        }

        this.result = result;
    }

    private static void rejectIncompatibleResult(Object result, Class<?> resultType) {
        throw new IllegalArgumentException("incompatible result: " + result +
                                           " (expected type: " + resultType.getName() + ')');
    }

    @JsonProperty
    public int replicaId() {
        return replicaId;
    }

    @JsonProperty
    public Command<T> command() {
        return command;
    }

    @JsonProperty
    public T result() {
        return result;
    }

    @Override
    public int hashCode() {
        return replicaId() * 31 + command().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ReplicationLog)) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        @SuppressWarnings("unchecked")
        final ReplicationLog<Object> that = (ReplicationLog<Object>) obj;

        return replicaId() == that.replicaId() &&
               Objects.equals(result(), that.result()) &&
               command().equals(that.command());
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder(64);

        buf.append("(replicaId: ");
        buf.append(replicaId());
        buf.append(", command: ");
        buf.append(command());
        buf.append(", result: ");
        buf.append(result());
        buf.append(')');

        return buf.toString();
    }
}
