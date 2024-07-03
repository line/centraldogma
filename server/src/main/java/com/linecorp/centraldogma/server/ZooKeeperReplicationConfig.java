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

package com.linecorp.centraldogma.server;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.centraldogma.server.CentralDogmaConfig.convertValue;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import io.netty.util.NetUtil;

/**
 * ZooKeeper-based replication configuration.
 */
public final class ZooKeeperReplicationConfig implements ReplicationConfig {

    private static final int DEFAULT_TIMEOUT_MILLIS = 10000;
    private static final int DEFAULT_NUM_WORKERS = 16;
    private static final int DEFAULT_MAX_LOG_COUNT = 1024;
    private static final long DEFAULT_MIN_LOG_AGE_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final String DEFAULT_SECRET = "ch4n63m3";

    private final int serverId;
    private final Map<Integer, ZooKeeperServerConfig> servers;
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
    public ZooKeeperReplicationConfig(int serverId, Map<Integer, ZooKeeperServerConfig> servers) {
        this(serverId, servers, null, null, null, null, null, null);
    }

    @VisibleForTesting
    ZooKeeperReplicationConfig(
            int serverId, Map<Integer, ZooKeeperServerConfig> servers, String secret,
            Map<String, String> additionalProperties,
            int timeoutMillis, int numWorkers, int maxLogCount, long minLogAgeMillis) {
        this(Integer.valueOf(serverId), servers, secret, additionalProperties, Integer.valueOf(timeoutMillis),
             Integer.valueOf(numWorkers), Integer.valueOf(maxLogCount), Long.valueOf(minLogAgeMillis));
    }

    @JsonCreator
    ZooKeeperReplicationConfig(@JsonProperty("serverId") @Nullable Integer serverId,
                               @JsonProperty(value = "servers", required = true)
                               @JsonDeserialize(keyAs = Integer.class, contentAs = ZooKeeperServerConfig.class)
                               Map<Integer, ZooKeeperServerConfig> servers,
                               @JsonProperty("secret") @Nullable String secret,
                               @JsonProperty("additionalProperties")
                               @JsonDeserialize(keyAs = String.class, contentAs = String.class)
                               @Nullable Map<String, String> additionalProperties,
                               @JsonProperty("timeoutMillis") @Nullable Integer timeoutMillis,
                               @JsonProperty("numWorkers") @Nullable Integer numWorkers,
                               @JsonProperty("maxLogCount") @Nullable Integer maxLogCount,
                               @JsonProperty("minLogAgeMillis") @Nullable Long minLogAgeMillis) {

        requireNonNull(servers, "servers");
        this.serverId = serverId != null ? serverId : findServerId(servers);
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

    private static int findServerId(Map<Integer, ZooKeeperServerConfig> servers) {
        int serverId = -1;
        try {
            for (final Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
                 e.hasMoreElements();) {
                serverId = findServerId(servers, serverId, e.nextElement());
            }
        } catch (SocketException e) {
            throw new IllegalStateException("failed to retrieve the network interface list", e);
        }

        if (serverId < 0) {
            throw new IllegalStateException(
                    "failed to auto-detect server ID because there is no matching IP address.");
        }

        return serverId;
    }

    private static int findServerId(Map<Integer, ZooKeeperServerConfig> servers, int currentServerId,
                                    NetworkInterface iface) {
        for (final Enumeration<InetAddress> ea = iface.getInetAddresses(); ea.hasMoreElements();) {
            currentServerId = findServerId(servers, currentServerId, ea.nextElement());
        }
        return currentServerId;
    }

    private static int findServerId(Map<Integer, ZooKeeperServerConfig> servers, int currentServerId,
                                    InetAddress addr) {
        final String ip = NetUtil.toAddressString(addr, true);
        for (Entry<Integer, ZooKeeperServerConfig> entry : servers.entrySet()) {
            final String zkAddr;
            try {
                zkAddr = NetUtil.toAddressString(InetAddress.getByName(entry.getValue().host()), true);
            } catch (UnknownHostException uhe) {
                throw new IllegalStateException(
                        "failed to resolve the IP address of the server name: " + entry.getValue().host());
            }

            if (zkAddr.equals(ip)) {
                final int serverId = entry.getKey().intValue();
                if (currentServerId < 0) {
                    currentServerId = serverId;
                } else if (currentServerId != serverId) {
                    throw new IllegalStateException(
                            "cannot auto-detect server ID because there are more than one IP address match. " +
                            "Both server ID " + currentServerId + " and " + serverId +
                            " have a matching IP address. Consider specifying server ID explicitly.");
                }
            }
        }
        return currentServerId;
    }

    @Override
    public ReplicationMethod method() {
        return ReplicationMethod.ZOOKEEPER;
    }

    /**
     * Returns the ID of this ZooKeeper server in {@link #servers()}.
     */
    @JsonProperty
    public int serverId() {
        return serverId;
    }

    /**
     * Returns the configuration of this ZooKeeper server in {@link #servers()}.
     */
    public ZooKeeperServerConfig serverConfig() {
        return servers.get(serverId);
    }

    /**
     * Returns the configuration of all ZooKeeper servers, keyed by their server IDs.
     */
    @JsonProperty
    public Map<Integer, ZooKeeperServerConfig> servers() {
        return servers;
    }

    /**
     * Returns the secret string used for authenticating the ZooKeeper peers.
     */
    public String secret() {
        return firstNonNull(convertValue(secret, "replication.secret"), DEFAULT_SECRET);
    }

    /**
     * Returns the additional ZooKeeper properties.
     * If unspecified, an empty {@link Map} is returned.
     */
    @JsonProperty
    public Map<String, String> additionalProperties() {
        return additionalProperties;
    }

    /**
     * Returns the ZooKeeper timeout, in milliseconds.
     * If unspecified, the default of {@value #DEFAULT_TIMEOUT_MILLIS} is returned.
     */
    @JsonProperty
    public int timeoutMillis() {
        return timeoutMillis;
    }

    /**
     * Returns the number of worker threads dedicated for replication.
     * If unspecified, the default of {@value #DEFAULT_NUM_WORKERS} is returned.
     */
    @JsonProperty
    public int numWorkers() {
        return numWorkers;
    }

    /**
     * Returns the maximum number of log items to keep in ZooKeeper. Note that the log items will still not be
     * removed if they are younger than {@link #minLogAgeMillis()}.
     * If unspecified, the default of {@value #DEFAULT_MAX_LOG_COUNT} is returned.
     */
    @JsonProperty
    public int maxLogCount() {
        return maxLogCount;
    }

    /**
     * Returns the minimum allowed age of log items before they are removed from ZooKeeper.
     * If unspecified, the default of 1 hour is returned.
     */
    @JsonProperty
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
