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

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.netty.util.NetUtil;

/**
 * ZooKeeper-based replication configuration spec.
 */
public interface ZooKeeperReplicationConfigSpec extends ReplicationConfig {
    int DEFAULT_TIMEOUT_MILLIS = 10000;
    int DEFAULT_NUM_WORKERS = 16;
    int DEFAULT_MAX_LOG_COUNT = 1024;
    long DEFAULT_MIN_LOG_AGE_MILLIS = TimeUnit.DAYS.toMillis(1);
    String DEFAULT_SECRET = "ch4n63m3";

    /**
     * Find a server id from given zooKeeper server configs.
     * @param servers zooKeeper server configs.
     * @return serverId
     */
    static int findServerId(Map<Integer, ZooKeeperServerConfigSpec> servers) {
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

    /**
     * Find a server id from given zooKeeper server configs.
     * @param servers ZooKeeper server configs.
     * @param currentServerId Current server id.
     * @param iface The network interface for current server instance.
     * @return serverId
     */
    static int findServerId(Map<Integer, ZooKeeperServerConfigSpec> servers, int currentServerId,
                            NetworkInterface iface) {
        for (final Enumeration<InetAddress> ea = iface.getInetAddresses(); ea.hasMoreElements();) {
            currentServerId = findServerId(servers, currentServerId, ea.nextElement());
        }
        return currentServerId;
    }

    /**
     * Find a server id from given zooKeeper server configs.
     * @param servers ZooKeeper server configs.
     * @param currentServerId Current server id.
     * @param addr The inet address which same as current server instance.
     * @return serverId
     */
    static int findServerId(Map<Integer, ZooKeeperServerConfigSpec> servers, int currentServerId,
                            InetAddress addr) {
        final String ip = NetUtil.toAddressString(addr, true);
        for (Map.Entry<Integer, ZooKeeperServerConfigSpec> entry : servers.entrySet()) {
            final String zkAddr;
            try {
                zkAddr = NetUtil.toAddressString(InetAddress.getByName(entry.getValue().host()), true);
            } catch (UnknownHostException uhe) {
                throw new IllegalStateException(
                        "failed to resolve the IP address of the server name: " + entry.getValue().host());
            }

            if (zkAddr.equals(ip)) {
                final int serverId = entry.getKey();
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

    /**
     * Returns the ID of this ZooKeeper server in {@link #servers()}.
     */
    int serverId();

    /**
     * Returns the configuration of this ZooKeeper server in {@link #servers()}.
     */
    ZooKeeperServerConfigSpec serverConfig();

    /**
     * Returns the configuration of all ZooKeeper servers, keyed by their server IDs.
     */
    Map<Integer, ZooKeeperServerConfigSpec> servers();

    /**
     * Returns the secret string used for authenticating the ZooKeeper peers.
     */
    String secret();

    /**
     * Returns the additional ZooKeeper properties.
     * If unspecified, an empty {@link java.util.Map} is returned.
     */
    Map<String, String> additionalProperties();

    /**
     * Returns the ZooKeeper timeout, in milliseconds.
     * If unspecified, the default of {@value #DEFAULT_TIMEOUT_MILLIS} is returned.
     */
    int timeoutMillis();

    /**
     * Returns the number of worker threads dedicated for replication.
     * If unspecified, the default of {@value #DEFAULT_NUM_WORKERS} is returned.
     */
    int numWorkers();

    /**
     * Returns the maximum number of log items to keep in ZooKeeper. Note that the log items will still not be
     * removed if they are younger than {@link #minLogAgeMillis()}.
     * If unspecified, the default of {@value #DEFAULT_MAX_LOG_COUNT} is returned.
     */
    int maxLogCount();

    /**
     * Returns the minimum allowed age of log items before they are removed from ZooKeeper.
     * If unspecified, the default of 1 hour is returned.
     */
    long minLogAgeMillis();
}
