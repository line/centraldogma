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

import static java.util.Objects.requireNonNull;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntBiFunction;

import org.apache.curator.test.InstanceSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.centraldogma.server.ZooKeeperServerConfig;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.testing.internal.TemporaryFolder;

final class ClusterBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ClusterBuilder.class);

    private ToIntBiFunction<Integer, Integer> weightMappingFunction = (groupId, serverId) -> 1;
    private int numReplicas = 5;
    private int numGroups = 1;
    private boolean autoStart = true;

    ClusterBuilder numReplicas(int numReplicas) {
        this.numReplicas = numReplicas;
        return this;
    }

    ClusterBuilder numGroup(int numGroups) {
        this.numGroups = numGroups;
        return this;
    }

    ClusterBuilder autoStart(boolean autoStart) {
        this.autoStart = autoStart;
        return this;
    }

    ClusterBuilder weightMappingFunction(ToIntBiFunction<Integer, Integer> function) {
        requireNonNull(function, "function");
        weightMappingFunction = function;
        return this;
    }

    Cluster build(Supplier<Function<Command<?>, CompletableFuture<?>>> commandExecutorSupplier)
            throws Exception {
        requireNonNull(commandExecutorSupplier, "commandExecutorSupplier");
        // Each replica requires 3 ports, for client, quorum and election.
        final List<ServerSocket> quorumPorts = new ArrayList<>();
        final List<ServerSocket> electionPorts = new ArrayList<>();
        for (int i = 0; i < numReplicas; i++) {
            quorumPorts.add(new ServerSocket(0));
            electionPorts.add(new ServerSocket(0));
        }

        final TemporaryFolder tempFolder = new TemporaryFolder();
        tempFolder.create();
        final List<InstanceSpec> specs = new ArrayList<>();
        final Map<Integer, ZooKeeperServerConfig> servers = new HashMap<>();
        final int groupSize = numReplicas / numGroups;
        for (int i = 0; i < numReplicas; i++) {
            final int serverId = i + 1;
            final InstanceSpec spec = new InstanceSpec(
                    tempFolder.newFolder().toFile(),
                    0,
                    electionPorts.get(i).getLocalPort(),
                    quorumPorts.get(i).getLocalPort(),
                    false,
                    serverId);

            specs.add(spec);
            final Integer groupId;
            if (numGroups == 1) {
                groupId = null;
            } else {
                groupId = (i / groupSize) + 1;
            }

            final int weight = weightMappingFunction.applyAsInt(groupId, serverId);
            servers.put(spec.getServerId(),
                        new ZooKeeperServerConfig("127.0.0.1", spec.getQuorumPort(), spec.getElectionPort(), 0,
                                                  groupId, weight));
        }

        logger.debug("Creating a cluster: {}", servers);

        for (int i = 0; i < numReplicas; i++) {
            quorumPorts.get(i).close();
            electionPorts.get(i).close();
        }

        final Builder<Replica> builder = ImmutableList.builder();
        for (InstanceSpec spec : specs) {
            final Replica r = new Replica(spec, servers, commandExecutorSupplier.get(), autoStart);
            builder.add(r);
        }

        final List<Replica> replicas = builder.build();
        if (autoStart) {
            replicas.forEach(Replica::awaitStartup);
        }
        return new Cluster(replicas);
    }
}
