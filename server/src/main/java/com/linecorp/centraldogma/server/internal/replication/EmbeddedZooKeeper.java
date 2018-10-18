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

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.zookeeper.server.DatadirCleanupManager;
import org.apache.zookeeper.server.PurgeTxnLog;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class EmbeddedZooKeeper extends QuorumPeer {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddedZooKeeper.class);

    static final String SASL_SERVER_LOGIN_CONTEXT = "QuorumServer";
    static final String SASL_LEARNER_LOGIN_CONTEXT = "QuorumLearner";

    private final ServerCnxnFactory cnxnFactory;
    private final DatadirCleanupManager purgeManager;

    EmbeddedZooKeeper(QuorumPeerConfig zkCfg) throws IOException {
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
}
