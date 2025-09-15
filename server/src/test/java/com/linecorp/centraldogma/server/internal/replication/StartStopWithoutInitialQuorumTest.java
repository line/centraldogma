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

import java.io.File;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.curator.test.InstanceSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.centraldogma.server.CentralDogma;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfig;
import com.linecorp.centraldogma.server.ZooKeeperServerConfig;
import com.linecorp.centraldogma.testing.internal.FlakyTest;

import io.netty.util.NetUtil;

/**
 * Makes sure that we can stop a replica that's waiting for the initial quorum.
 */
@FlakyTest
class StartStopWithoutInitialQuorumTest {

    @TempDir
    static File tmpDir;

    @Test
    void test() {
        final InetSocketAddress port = new InetSocketAddress(NetUtil.LOCALHOST4, 0);
        final CentralDogma dogma = new CentralDogmaBuilder(tmpDir)
                .port(port, SessionProtocol.HTTP)
                .build();
        // Start without any quorum first to create internal data structures.
        dogma.start().join();
        dogma.stop().join();

        // Set up a cluster of two replicas where the second replica is always unavailable,
        final int quorumPort = InstanceSpec.getRandomPort();
        final int electionPort = InstanceSpec.getRandomPort();
        final int clientPort = InstanceSpec.getRandomPort();
        final Map<Integer, ZooKeeperServerConfig> servers =
                ImmutableMap.of(1,
                                new ZooKeeperServerConfig("127.0.0.1", quorumPort, electionPort,
                                                          clientPort, /* groupId */ null, /* weight */ 1),
                                2,
                                new ZooKeeperServerConfig("127.0.0.1", 1, 1,
                                                          1, /* groupId */ null, /* weight */ 1));
        final CentralDogma dogma2 = new CentralDogmaBuilder(tmpDir)
                .port(port, SessionProtocol.HTTP)
                .replication(new ZooKeeperReplicationConfig(1, servers))
                .build();
        final CompletableFuture<Void> start = dogma2.start();
        dogma2.stop().join();
        start.join();
    }
}
