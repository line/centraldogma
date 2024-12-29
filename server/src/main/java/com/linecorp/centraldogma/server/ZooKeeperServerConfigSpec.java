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

package com.linecorp.centraldogma.server;

import static com.google.common.base.Preconditions.checkArgument;

import javax.annotation.Nullable;

/**
 * Represents the address and port numbers of a ZooKeeper node.
 */
public interface ZooKeeperServerConfigSpec {
    /**
     * Validate port.
     * @param port Port.
     * @param name Port name to make error message.
     * @return Validated port
     */
    static int validatePort(int port, String name) {
        checkArgument(port > 0 && port <= 65535, "%s: %s (expected: 1-65535)", name, port);
        return port;
    }

    /**
     * Returns the IP address or host name of the ZooKeeper server.
     */
    String host();

    /**
     * Returns the quorum port number.
     */
    int quorumPort();

    /**
     * Returns the election port number.
     */
    int electionPort();

    /**
     * Returns the client port number.
     */
    int clientPort();

    /**
     * Returns the group ID to use hierarchical quorums.
     */
    @Nullable
    Integer groupId();

    /**
     * Returns the weight of the ZooKeeper server.
     */
    int weight();
}
