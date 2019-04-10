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

import static com.google.common.base.MoreObjects.firstNonNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.apache.zookeeper.server.DatadirCleanupManager;
import org.apache.zookeeper.server.PurgeTxnLog;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;

final class EmbeddedZooKeeper extends QuorumPeer {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddedZooKeeper.class);

    static final String SASL_SERVER_LOGIN_CONTEXT = "QuorumServer";
    static final String SASL_LEARNER_LOGIN_CONTEXT = "QuorumLearner";

    private static final ServerStats EMPTY_STATS = new EmptyServerStats();

    private final ServerCnxnFactory cnxnFactory;
    private final DatadirCleanupManager purgeManager;

    EmbeddedZooKeeper(QuorumPeerConfig zkCfg, MeterRegistry meterRegistry) throws IOException {
        cnxnFactory = createCnxnFactory(zkCfg);

        setTxnFactory(new FileTxnSnapLog(zkCfg.getDataLogDir(), zkCfg.getDataDir()));
        enableLocalSessions(zkCfg.areLocalSessionsEnabled());
        enableLocalSessionsUpgrading(zkCfg.isLocalSessionsUpgradingEnabled());
        setElectionType(zkCfg.getElectionAlg());
        setMyid(zkCfg.getServerId());
        setTickTime(zkCfg.getTickTime());
        setMinSessionTimeout(zkCfg.getMinSessionTimeout());
        setMaxSessionTimeout(zkCfg.getMaxSessionTimeout());
        setInitLimit(zkCfg.getInitLimit());
        setSyncLimit(zkCfg.getSyncLimit());
        setConfigFileName(zkCfg.getConfigFilename());
        setZKDatabase(new ZKDatabase(getTxnFactory()));
        setQuorumVerifier(zkCfg.getQuorumVerifier(), false);
        if (zkCfg.getLastSeenQuorumVerifier() != null) {
            setLastSeenQuorumVerifier(zkCfg.getLastSeenQuorumVerifier(), false);
        }
        initConfigInZKDatabase();
        setCnxnFactory(cnxnFactory);
        setLearnerType(zkCfg.getPeerType());
        setSyncEnabled(zkCfg.getSyncEnabled());
        setQuorumListenOnAllIPs(zkCfg.getQuorumListenOnAllIPs());

        configureSasl();

        purgeManager = new DatadirCleanupManager(zkCfg.getDataDir(), zkCfg.getDataLogDir(),
                                                 zkCfg.getSnapRetainCount(), zkCfg.getPurgeInterval());

        // Bind meters that indicates the ZooKeeper stats.
        TimeGauge.builder("zookeeper.latency", this, TimeUnit.MILLISECONDS,
                          peer -> serverStats(peer).getAvgLatency())
                 .tag("type", "avg")
                 .register(meterRegistry);
        TimeGauge.builder("zookeeper.latency", this, TimeUnit.MILLISECONDS,
                          peer -> serverStats(peer).getMaxLatency())
                 .tag("type", "max")
                 .register(meterRegistry);
        TimeGauge.builder("zookeeper.latency", this, TimeUnit.MILLISECONDS,
                          peer -> serverStats(peer).getMinLatency())
                 .tag("type", "min")
                 .register(meterRegistry);

        Gauge.builder("zookeeper.outstandingRequests", this,
                      peer -> serverStats(peer).getOutstandingRequests())
             .register(meterRegistry);

        Gauge.builder("zookeeper.lastProcessedZxid", this,
                      peer -> serverStats(peer).getLastProcessedZxid())
             .register(meterRegistry);

        Gauge.builder("zookeeper.dataDirSize", this,
                      peer -> serverStats(peer).getDataDirSize())
             .baseUnit("bytes")
             .register(meterRegistry);

        Gauge.builder("zookeeper.logDirSize", this,
                      peer -> serverStats(peer).getLogDirSize())
             .baseUnit("bytes")
             .register(meterRegistry);

        FunctionCounter.builder("zookeeper.packetsReceived", this,
                                peer -> serverStats(peer).getPacketsReceived())
                       .register(meterRegistry);

        FunctionCounter.builder("zookeeper.packetsSent", this,
                                peer -> serverStats(peer).getPacketsSent())
                       .register(meterRegistry);

        Gauge.builder("zookeeper.aliveClientConnections", this,
                      peer -> serverStats(peer).getNumAliveClientConnections())
             .register(meterRegistry);

        Gauge.builder("zookeeper.state.leader", this,
                      peer -> "leader".equals(serverStats(peer).getServerState()) ? 1 : 0)
             .register(meterRegistry);

        Gauge.builder("zookeeper.state.follower", this,
                      peer -> "follower".equals(serverStats(peer).getServerState()) ? 1 : 0)
             .register(meterRegistry);

        Gauge.builder("zookeeper.state.observer", this,
                      peer -> "observer".equals(serverStats(peer).getServerState()) ? 1 : 0)
             .register(meterRegistry);

        Gauge.builder("zookeeper.state.readOnly", this,
                      peer -> "read-only".equals(serverStats(peer).getServerState()) ? 1 : 0)
             .register(meterRegistry);

        Gauge.builder("zookeeper.state.unknown", this,
                      peer -> {
                          final String state = serverStats(peer).getServerState();
                          if (state == null) {
                              return 1;
                          }
                          switch (state) {
                              case "leader":
                              case "follower":
                              case "observer":
                              case "read-only":
                                  return 0;
                              default:
                                  return 1;
                          }
                      })
             .register(meterRegistry);
    }

    private static ServerStats serverStats(@Nullable EmbeddedZooKeeper peer) {
        if (peer == null) {
            return EMPTY_STATS;
        }

        final ZooKeeperServer activeServer = peer.getActiveServer();
        if (activeServer == null) {
            return EMPTY_STATS;
        }

        final ServerStats stats = activeServer.serverStats();
        return firstNonNull(stats, EMPTY_STATS);
    }

    private static ServerCnxnFactory createCnxnFactory(QuorumPeerConfig zkCfg) throws IOException {
        final InetSocketAddress bindAddr = zkCfg.getClientPortAddress();
        final ServerCnxnFactory cnxnFactory = ServerCnxnFactory.createFactory();
        // Listen only on 127.0.0.1 because we do not want to expose ZooKeeper to others.
        cnxnFactory.configure(new InetSocketAddress("127.0.0.1", bindAddr != null ? bindAddr.getPort() : 0),
                              zkCfg.getMaxClientCnxns());
        return cnxnFactory;
    }

    private void configureSasl() {
        quorumServerSaslAuthRequired = true;
        quorumLearnerSaslAuthRequired = true;
        quorumServerLoginContext = SASL_SERVER_LOGIN_CONTEXT;
        quorumLearnerLoginContext = SASL_LEARNER_LOGIN_CONTEXT;
    }

    @Override
    public synchronized void start() {
        purgeTxnLogs();
        purgeManager.start();
        super.start();
    }

    @Override
    public void shutdown() {
        // Close the network stack first so that the shutdown process is done quickly.
        cnxnFactory.shutdown();
        purgeManager.shutdown();
        super.shutdown();
    }

    private void purgeTxnLogs() {
        logger.info("Purging old ZooKeeper snapshots and logs ..");
        try {
            PurgeTxnLog.purge(purgeManager.getDataLogDir(),
                              purgeManager.getSnapDir(),
                              purgeManager.getSnapRetainCount());
            logger.info("Purged old ZooKeeper snapshots and logs.");
        } catch (IOException e) {
            logger.error("Failed to purge old ZooKeeper snapshots and logs:", e);
        }
    }

    private static final class EmptyServerStats extends ServerStats {
        EmptyServerStats() {
            super(new Provider() {
                @Override
                public long getOutstandingRequests() {
                    return 0;
                }

                @Override
                public long getLastProcessedZxid() {
                    return 0;
                }

                @Override
                public String getState() {
                    return "unknown";
                }

                @Override
                public int getNumAliveConnections() {
                    return 0;
                }

                @Override
                public long getDataDirSize() {
                    return 0;
                }

                @Override
                public long getLogDirSize() {
                    return 0;
                }
            });
        }
    }
}
