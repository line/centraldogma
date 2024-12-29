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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.centraldogma.server.CentralDogmaConfig.convertValue;
import static java.util.Objects.requireNonNull;

import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

/**
 * ZooKeeper-based replication configuration.
 */
public final class ZooKeeperReplicationConfig implements ZooKeeperReplicationConfigSpec {

    private final int serverId;
    private final Map<Integer, ZooKeeperServerConfigSpec> servers;
    @Nullable
    private final String secret;
    private final Map<String, String> additionalProperties;
    private final int timeoutMillis;
    private final int numWorkers;
    private final int maxLogCount;
    private final long minLogAgeMillis;

    /**
     * Creates a new replication configuration.
     *
     * @param serverId the ID of this ZooKeeper server in {@code servers}
     * @param servers the ZooKeeper server addresses, keyed by their ZooKeeper server IDs
     */
    public ZooKeeperReplicationConfig(int serverId, Map<Integer, ZooKeeperServerConfigSpec> servers) {
        this(serverId, servers, null, null, null, null, null, null);
    }

    @VisibleForTesting
    ZooKeeperReplicationConfig(
            int serverId, Map<Integer, ZooKeeperServerConfigSpec> servers, String secret,
            Map<String, String> additionalProperties,
            int timeoutMillis, int numWorkers, int maxLogCount, long minLogAgeMillis) {
        this(Integer.valueOf(serverId), servers, secret, additionalProperties, Integer.valueOf(timeoutMillis),
             Integer.valueOf(numWorkers), Integer.valueOf(maxLogCount), Long.valueOf(minLogAgeMillis));
    }

    @JsonCreator
    ZooKeeperReplicationConfig(@JsonProperty("serverId") @Nullable Integer serverId,
                               @JsonProperty(value = "servers", required = true)
                               @JsonDeserialize(keyAs = Integer.class, contentAs = ZooKeeperServerConfig.class)
                               Map<Integer, ZooKeeperServerConfigSpec> servers,
                               @JsonProperty("secret") @Nullable String secret,
                               @JsonProperty("additionalProperties")
                               @JsonDeserialize(keyAs = String.class, contentAs = String.class)
                               @Nullable Map<String, String> additionalProperties,
                               @JsonProperty("timeoutMillis") @Nullable Integer timeoutMillis,
                               @JsonProperty("numWorkers") @Nullable Integer numWorkers,
                               @JsonProperty("maxLogCount") @Nullable Integer maxLogCount,
                               @JsonProperty("minLogAgeMillis") @Nullable Long minLogAgeMillis) {

        requireNonNull(servers, "servers");
        this.serverId = serverId != null ? serverId : ZooKeeperReplicationConfigSpec.findServerId(servers);
        checkArgument(this.serverId > 0, "serverId: %s (expected: > 0)", serverId);
        this.secret = secret;
        checkArgument(!secret().isEmpty(), "secret is empty.");

        servers.forEach((id, server) -> {
            checkArgument(id > 0, "'servers' contains non-positive server ID: %s (expected: > 0)", id);
        });
        this.servers = ImmutableMap.copyOf(servers);

        checkArgument(!this.servers.isEmpty(), "servers is empty.");
        checkArgument(this.servers.containsKey(this.serverId),
                      "servers must contain the server '%s'.", this.serverId);

        this.additionalProperties = firstNonNull(additionalProperties, ImmutableMap.of());

        this.timeoutMillis =
                timeoutMillis == null || timeoutMillis <= 0 ? DEFAULT_TIMEOUT_MILLIS : timeoutMillis;

        this.numWorkers =
                numWorkers == null || numWorkers <= 0 ? DEFAULT_NUM_WORKERS : numWorkers;

        this.maxLogCount =
                maxLogCount == null || maxLogCount <= 0 ? DEFAULT_MAX_LOG_COUNT : maxLogCount;

        this.minLogAgeMillis =
                minLogAgeMillis == null || minLogAgeMillis <= 0 ? DEFAULT_MIN_LOG_AGE_MILLIS : minLogAgeMillis;
    }

    @Override
    public ReplicationMethod method() {
        return ReplicationMethod.ZOOKEEPER;
    }

    @JsonProperty
    @Override
    public int serverId() {
        return serverId;
    }

    @Override
    public ZooKeeperServerConfigSpec serverConfig() {
        return servers.get(serverId);
    }

    @JsonProperty
    @Override
    public Map<Integer, ZooKeeperServerConfigSpec> servers() {
        return servers;
    }

    @Override
    public String secret() {
        return firstNonNull(convertValue(secret, "replication.secret"), DEFAULT_SECRET);
    }

    @JsonProperty
    @Override
    public Map<String, String> additionalProperties() {
        return additionalProperties;
    }

    @JsonProperty
    @Override
    public int timeoutMillis() {
        return timeoutMillis;
    }

    @JsonProperty
    @Override
    public int numWorkers() {
        return numWorkers;
    }

    @JsonProperty
    @Override
    public int maxLogCount() {
        return maxLogCount;
    }

    @JsonProperty
    @Override
    public long minLogAgeMillis() {
        return minLogAgeMillis;
    }

    @Override
    public int hashCode() {
        return serverId;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ZooKeeperReplicationConfig)) {
            return false;
        }

        if (obj == this) {
            return true;
        }

        final ZooKeeperReplicationConfig that = (ZooKeeperReplicationConfig) obj;

        return serverId() == that.serverId() &&
               servers().equals(that.servers()) &&
               additionalProperties().equals(that.additionalProperties()) &&
               timeoutMillis() == that.timeoutMillis() &&
               numWorkers() == that.numWorkers() &&
               maxLogCount() == that.maxLogCount() &&
               minLogAgeMillis() == that.minLogAgeMillis();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("serverId", serverId())
                          .add("servers", servers())
                          .add("additionalProperties", additionalProperties())
                          .add("timeoutMillis", timeoutMillis())
                          .add("numWorkers", numWorkers())
                          .add("maxLogCount", maxLogCount())
                          .add("minLogAgeMillis", minLogAgeMillis()).toString();
    }
}
