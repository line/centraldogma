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

import static com.linecorp.centraldogma.server.internal.replication.ZooKeeperCommandExecutor.DEFAULT_MAX_LOG_COUNT;
import static com.linecorp.centraldogma.server.internal.replication.ZooKeeperCommandExecutor.DEFAULT_MIN_LOG_AGE_MILLIS;
import static com.linecorp.centraldogma.server.internal.replication.ZooKeeperCommandExecutor.DEFAULT_NUM_WORKERS;
import static com.linecorp.centraldogma.server.internal.replication.ZooKeeperCommandExecutor.DEFAULT_TIMEOUT_MILLIS;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.server.internal.replication.ZooKeeperCommandExecutor;

/**
 * ZooKeeper-based replication configuration.
 */
public final class ZooKeeperReplicationConfig implements ReplicationConfig {

    private final String connectionString;
    private final String pathPrefix;
    private final int timeoutMillis;
    private final int numWorkers;
    private final int maxLogCount;
    private final long minLogAgeMillis;

    /**
     * Creates a new replication configuration.
     *
     * @param connectionString the ZooKeeper connection string.
     *                         e.g. {@code "zk1.example.com:2181,zk2.example.com:2181,zk3.example.com:2181"}
     * @param pathPrefix the ZooKeeper path prefix. e.g. {@code "/service/dogma"}
     */
    public ZooKeeperReplicationConfig(String connectionString, String pathPrefix) {
        this(connectionString, pathPrefix, null, null, null, null);
    }

    /**
     * Creates a new replication configuration.
     *
     * @param connectionString the ZooKeeper connection string.
     *                         e.g. {@code "zk1.example.com:2181,zk2.example.com:2181,zk3.example.com:2181"}
     * @param pathPrefix the ZooKeeper path prefix. e.g. {@code "/service/dogma"}
     * @param timeoutMillis the ZooKeeper timeout, in milliseconds
     * @param numWorkers the number of worker threads dedicated for replication
     * @param maxLogCount the maximum number of log items to keep in ZooKeeper. Note that the log items will
     *                    still not be removed if they are younger than {@code minLogAgeMillis}.
     * @param minLogAgeMillis the minimum allowed age of log items before they are removed from ZooKeeper
     */
    public ZooKeeperReplicationConfig(String connectionString, String pathPrefix,
            int timeoutMillis, int numWorkers, int maxLogCount, long minLogAgeMillis) {
        this(connectionString, pathPrefix, Integer.valueOf(timeoutMillis),
             Integer.valueOf(numWorkers), Integer.valueOf(maxLogCount), Long.valueOf(minLogAgeMillis));
    }

    @JsonCreator
    ZooKeeperReplicationConfig(@JsonProperty("connectionString") String connectionString,
                               @JsonProperty("pathPrefix") String pathPrefix,
                               @JsonProperty("timeoutMillis") Integer timeoutMillis,
                               @JsonProperty("numWorkers") Integer numWorkers,
                               @JsonProperty("maxLogCount") Integer maxLogCount,
                               @JsonProperty("minLogAgeMillis") Long minLogAgeMillis) {

        this.connectionString = requireNonNull(connectionString, "connectionString");
        this.pathPrefix = requireNonNull(pathPrefix, "pathPrefix");

        int timeoutMillisValue = timeoutMillis != null ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS;
        if (timeoutMillisValue <= 0) {
            timeoutMillisValue = DEFAULT_TIMEOUT_MILLIS;
        }

        this.timeoutMillis = timeoutMillisValue;

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

    /**
     * Returns the ZooKeeper connection string.
     * e.g. {@code "zk1.example.com:2181,zk2.example.com:2181,zk3.example.com:2181"}
     */
    @JsonProperty
    public String connectionString() {
        return connectionString;
    }

    /**
     * Returns the ZooKeeper path prefix. e.g. {@code "/service/dogma"}
     */
    @JsonProperty
    public String pathPrefix() {
        return pathPrefix;
    }

    /**
     * Returns the ZooKeeper timeout, in milliseconds.
     * If unspecified, the default of {@value ZooKeeperCommandExecutor#DEFAULT_TIMEOUT_MILLIS} is returned.
     */
    @JsonProperty
    public int timeoutMillis() {
        return timeoutMillis;
    }

    /**
     * Returns the number of worker threads dedicated for replication.
     * If unspecified, the default of {@value ZooKeeperCommandExecutor#DEFAULT_NUM_WORKERS} is returned.
     */
    @JsonProperty
    public int numWorkers() {
        return numWorkers;
    }

    /**
     * Returns the maximum number of log items to keep in ZooKeeper. Note that the log items will still not be
     * removed if they are younger than {@link #minLogAgeMillis()}.
     * If unspecified, the default of {@value ZooKeeperCommandExecutor#DEFAULT_MAX_LOG_COUNT} is returned.
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
        return connectionString().hashCode() * 31 +
               pathPrefix().hashCode();
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

        return connectionString().equals(that.connectionString()) &&
               pathPrefix().equals(that.pathPrefix()) &&
               timeoutMillis() == that.timeoutMillis() &&
               numWorkers() == that.numWorkers() &&
               maxLogCount() == that.maxLogCount() &&
               minLogAgeMillis() == that.minLogAgeMillis();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("connectionString", connectionString())
                          .add("pathPrefix", pathPrefix())
                          .add("timeoutMillis", timeoutMillis())
                          .add("numWorkers", numWorkers())
                          .add("maxLogCount", maxLogCount())
                          .add("minLogAgeMillis", minLogAgeMillis()).toString();
    }
}
