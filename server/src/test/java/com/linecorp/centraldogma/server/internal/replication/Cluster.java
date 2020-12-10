/*
 * Copyright 2020 LINE Corporation
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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.collect.ForwardingList;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.centraldogma.server.command.Command;

final class Cluster extends ForwardingList<Replica> implements SafeCloseable {

    static ClusterBuilder builder() {
        return new ClusterBuilder();
    }

    static Cluster of(Supplier<Function<Command<?>, CompletableFuture<?>>> commandExecutorSupplier)
            throws Exception {
        return builder().build(commandExecutorSupplier);
    }

    private final List<Replica> replicas;

    Cluster(List<Replica> replicas) {
        this.replicas = replicas;
    }

    @Override
    protected List<Replica> delegate() {
        return replicas;
    }

    @Override
    public void close() {
        for (Replica replica : this) {
            replica.commandExecutor().stop().join();
        }
    }
}
