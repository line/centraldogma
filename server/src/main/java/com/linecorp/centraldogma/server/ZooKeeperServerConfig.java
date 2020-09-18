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
package com.linecorp.centraldogma.server;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

/**
 * Represents the address and port numbers of a ZooKeeper node.
 */
public final class ZooKeeperServerConfig {

    private final String host;
    private final int quorumPort;
    private final int electionPort;
    private final int clientPort;
    @Nullable
    private final Integer groupId;
    private final int weight;

    /**
     * Creates a new instance.
     *
     * @param host the IP address or host name of the ZooKeeper server
     * @param quorumPort the quorum port number
     * @param electionPort the election port number
     * @param clientPort the client port number (0-65535)
     * @param groupId the group ID to use hierarchical quorums
     * @param weight the weight of the Zookeeper server
     */
    @JsonCreator
    public ZooKeeperServerConfig(@JsonProperty(value = "host", required = true) String host,
                                 @JsonProperty(value = "quorumPort", required = true) int quorumPort,
                                 @JsonProperty(value = "electionPort", required = true) int electionPort,
                                 @JsonProperty(value = "clientPort", defaultValue = "0") int clientPort,
                                 @JsonProperty("groupId") @Nullable Integer groupId,
                                 @JsonProperty(value = "weight", defaultValue = "1") int weight) {

        this.host = requireNonNull(host, "host");
        this.quorumPort = validatePort(quorumPort, "quorumPort");
        this.electionPort = validatePort(electionPort, "electionPort");

        checkArgument(clientPort >= 0 && clientPort <= 65535,
                      "clientPort: %s (expected: 0-65535)", clientPort);
        this.clientPort = clientPort;
        this.groupId = groupId;
        this.weight = weight;
    }

    private static int validatePort(int port, String name) {
        checkArgument(port > 0 && port <= 65535, "%s: %s (expected: 1-65535)", name, port);
        return port;
    }

    /**
     * Returns the IP address or host name of the ZooKeeper server.
     */
    @JsonProperty
    public String host() {
        return host;
    }

    /**
     * Returns the quorum port number.
     */
    @JsonProperty
    public int quorumPort() {
        return quorumPort;
    }

    /**
     * Returns the election port number.
     */
    @JsonProperty
    public int electionPort() {
        return electionPort;
    }

    /**
     * Returns the client port number.
     */
    @JsonProperty
    public int clientPort() {
        return clientPort;
    }

    /**
     * Returns the group ID to use hierarchical quorums.
     */
    @Nullable
    @JsonProperty
    public Integer groupId() {
        return groupId;
    }

    /**
     * Returns the weight of the ZooKeeper server.
     */
    @JsonProperty
    public int weight() {
        return weight;
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, quorumPort, electionPort, clientPort, groupId, weight);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final ZooKeeperServerConfig that = (ZooKeeperServerConfig) o;
        return host.equals(that.host) &&
               quorumPort == that.quorumPort &&
               electionPort == that.electionPort &&
               clientPort == that.clientPort &&
               Objects.equals(groupId, that.groupId) &&
               weight == that.weight;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("host", host)
                          .add("quorumPort", quorumPort)
                          .add("electionPort", electionPort)
                          .add("clientPort", clientPort)
                          .add("groupId", groupId)
                          .add("weight", weight)
                          .toString();
    }
}
