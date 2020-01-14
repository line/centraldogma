/*
 * Copyright 2018 LINE Corporation
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.curator.test.InstanceSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import com.google.common.collect.ImmutableMap;

import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.server.ZooKeeperAddress;
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfig;
import com.linecorp.centraldogma.testing.junit4.CentralDogmaRule;

/**
 * Makes sure that we can stop a replica that's waiting for the initial quorum.
 */
public class StartStopWithoutInitialQuorumTest {

    @Rule
    public final TestRule timeoutRule = new DisableOnDebug(new Timeout(1, TimeUnit.MINUTES));

    @Rule
    public final CentralDogmaRule dogma = new CentralDogmaRule() {
        @Override
        protected void before() {
            // Do not start yet.
        }

        @Override
        protected void configure(CentralDogmaBuilder builder) {
            // Set up a cluster of two replicas where the second replica is always unavailable,
            final int quorumPort = InstanceSpec.getRandomPort();
            final int electionPort = InstanceSpec.getRandomPort();
            final int clientPort = InstanceSpec.getRandomPort();

            builder.replication(new ZooKeeperReplicationConfig(
                    1, ImmutableMap.of(1, new ZooKeeperAddress("127.0.0.1",
                                                               quorumPort, electionPort, clientPort),
                                       2, new ZooKeeperAddress("127.0.0.1", 1, 1, 1))));
        }
    };

    @Test
    public void test() {
        final CompletableFuture<Void> startFuture = dogma.startAsync();
        dogma.stopAsync().join();
        startFuture.join();
    }
}
