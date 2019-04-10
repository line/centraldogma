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

package com.linecorp.centraldogma.server.internal.replication;

import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.transaction.CuratorOp;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.RetryForever;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.server.auth.DigestLoginModule;
import org.apache.zookeeper.server.auth.SASLAuthenticationProvider;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.escape.Escaper;
import com.google.common.escape.Escapers;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.ZooKeeperReplicationConfig;
import com.linecorp.centraldogma.server.command.AbstractCommandExecutor;
import com.linecorp.centraldogma.server.command.Command;
import com.linecorp.centraldogma.server.command.CommandExecutor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.netty.util.concurrent.DefaultThreadFactory;

public final class ZooKeeperCommandExecutor
        extends AbstractCommandExecutor implements PathChildrenCacheListener {

    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperCommandExecutor.class);
    private static final Escaper jaasValueEscaper =
            Escapers.builder().addEscape('\"', "\\\"").addEscape('\\', "\\\\").build();

    private static final String PATH_PREFIX = "/dogma";
    private static final int MAX_BYTES = 1024 * 1023; // Max size in document is 1M. but safety.

    // Log revision should be started at 0 and be increased by 1. Do not create any changes without creating
    // a log node, because otherwise the consistency of the log revision will be broken. Also, we should use
    // zk 3.4.x or later version, because deleting a log node in an older version will break consistency as
    // well.

    @VisibleForTesting
    static final String LOG_PATH = "logs";

    @VisibleForTesting
    static final String LOG_BLOCK_PATH = "log_blocks";

    @VisibleForTesting
    static final String LOCK_PATH = "lock";

    @VisibleForTesting
    static final String LEADER_PATH = "leader";

    private static final RetryPolicy RETRY_POLICY_ALWAYS = new RetryForever(500);
    private static final RetryPolicy RETRY_POLICY_NEVER = (retryCount, elapsedTimeMs, sleeper) -> false;

    private final ZooKeeperReplicationConfig cfg;
    private final File revisionFile;
    private final File zkConfFile;
    private final File zkDataDir;
    private final File zkLogDir;
    private final CommandExecutor delegate;
    private final MeterRegistry meterRegistry;

    private volatile EmbeddedZooKeeper quorumPeer;
    private volatile CuratorFramework curator;
    private volatile RetryPolicy retryPolicy = RETRY_POLICY_NEVER;
    private volatile ExecutorService executor;
    private volatile ExecutorService logWatcherExecutor;
    private volatile PathChildrenCache logWatcher;
    private volatile OldLogRemover oldLogRemover;
    private volatile ExecutorService leaderSelectorExecutor;
    private volatile LeaderSelector leaderSelector;
    private volatile boolean createdParentNodes;

    private class OldLogRemover implements LeaderSelectorListener {
        @Override
        public void stateChanged(CuratorFramework client, ConnectionState newState) {
            //ignore
        }

        @Override
        public void takeLeadership(CuratorFramework client) throws Exception {
            final ListenerInfo listenerInfo = ZooKeeperCommandExecutor.this.listenerInfo;
            if (listenerInfo == null) {
                // Stopped.
                return;
            }

            logger.info("Taking leadership: {}", replicaId());
            try {
                if (listenerInfo.onTakeLeadership != null) {
                    listenerInfo.onTakeLeadership.run();
                }

                while (curator.getState() == CuratorFrameworkState.STARTED) {
                    deleteLogs();
                    synchronized (this) {
                        wait();
                    }
                }
            } catch (InterruptedException e) {
                // Leader selector has been closed.
            } catch (Exception e) {
                logger.error("Leader stopped due to an unexpected exception:", e);
            } finally {
                logger.info("Releasing leadership: {}", replicaId());
                if (listenerInfo.onReleaseLeadership != null) {
                    listenerInfo.onReleaseLeadership.run();
                }

                if (ZooKeeperCommandExecutor.this.listenerInfo != null) {
                    // Requeue only when the executor is not stopped.
                    leaderSelector.requeue();
                }
            }
        }

        public synchronized void touch() {
            notify();
        }

        private void deleteLogs() throws Exception {
            final List<String> children = curator.getChildren().forPath(absolutePath(LOG_PATH));
            if (children.size() <= cfg.maxLogCount()) {
                return;
            }

            final long minAllowedTimestamp = System.currentTimeMillis() - cfg.minLogAgeMillis();
            final int targetCount = children.size() - cfg.maxLogCount();
            final List<String> deleted = new ArrayList<>(targetCount);
            children.sort(Comparator.comparingLong(Long::parseLong));
            try {
                for (int i = 0; i < targetCount; ++i) {
                    final String logPath = absolutePath(LOG_PATH, children.get(i));
                    final LogMeta meta = Jackson.readValue(curator.getData().forPath(logPath), LogMeta.class);

                    if (meta.timestamp() >= minAllowedTimestamp) {
                        // Do not delete the logs that are not old enough.
                        // We can break the loop here because the 'children' has been sorted by
                        // insertion order (sequence value).
                        break;
                    }

                    final List<CuratorOp> ops = new ArrayList<>();
                    ops.add(curator.transactionOp().delete().forPath(logPath));
                    for (long blockId : meta.blocks()) {
                        final String blockPath = absolutePath(LOG_BLOCK_PATH) + '/' + pathFromRevision(blockId);
                        ops.add(curator.transactionOp().delete().forPath(blockPath));
                    }

                    curator.transaction().forOperations(ops);
                    deleted.add(children.get(i));
                }
            } finally {
                logger.info("delete logs: {}", deleted);
            }
        }
    }

    private static final class ListenerInfo {
        long lastReplayedRevision;
        final Runnable onTakeLeadership;
        final Runnable onReleaseLeadership;

        ListenerInfo(long lastReplayedRevision,
                     @Nullable Runnable onTakeLeadership, @Nullable Runnable onReleaseLeadership) {

            this.lastReplayedRevision = lastReplayedRevision;
            this.onReleaseLeadership = onReleaseLeadership;
            this.onTakeLeadership = onTakeLeadership;
        }
    }

    private volatile ListenerInfo listenerInfo;

    public ZooKeeperCommandExecutor(ZooKeeperReplicationConfig cfg,
                                    File dataDir, CommandExecutor delegate,
                                    MeterRegistry meterRegistry,
                                    @Nullable Consumer<CommandExecutor> onTakeLeadership,
                                    @Nullable Consumer<CommandExecutor> onReleaseLeadership) {
        super(onTakeLeadership, onReleaseLeadership);

        this.cfg = requireNonNull(cfg, "cfg");
        requireNonNull(dataDir, "dataDir");
        revisionFile = new File(dataDir.getAbsolutePath() + File.separatorChar + "last_revision");
        zkConfFile = new File(dataDir.getAbsolutePath() + File.separatorChar +
                              "_zookeeper" + File.separatorChar + "config.properties");
        zkDataDir = new File(dataDir.getAbsolutePath() + File.separatorChar +
                             "_zookeeper" + File.separatorChar + "data");
        zkLogDir = new File(dataDir.getAbsolutePath() + File.separatorChar +
                            "_zookeeper" + File.separatorChar + "log");

        this.delegate = requireNonNull(delegate, "delegate");
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
    }

    @Override
    public int replicaId() {
        return cfg.serverId();
    }

    @Override
    protected void doStart(@Nullable Runnable onTakeLeadership,
                           @Nullable Runnable onReleaseLeadership) throws Exception {
        try {
            // Get the last replayed revision.
            final long lastReplayedRevision;
            try {
                lastReplayedRevision = getLastReplayedRevision();
                listenerInfo = new ListenerInfo(lastReplayedRevision, onTakeLeadership, onReleaseLeadership);
            } catch (Exception e) {
                throw new ReplicationException("failed to read " + revisionFile, e);
            }

            // Start the embedded ZooKeeper.
            quorumPeer = startZooKeeper();
            retryPolicy = RETRY_POLICY_ALWAYS;

            // Start the Curator framework.
            curator = CuratorFrameworkFactory.newClient(
                    "127.0.0.1:" + quorumPeer.getClientPort(), cfg.timeoutMillis(), cfg.timeoutMillis(),
                    (retryCount, elapsedTimeMs, sleeper) -> {
                        return retryPolicy.allowRetry(retryCount, elapsedTimeMs, sleeper);
                    });

            curator.start();

            // Start the log replay.
            logWatcherExecutor = Executors.newSingleThreadExecutor(
                    new DefaultThreadFactory("zookeeper-log-watcher", true));
            ExecutorServiceMetrics.monitor(meterRegistry, logWatcherExecutor,
                                           "zkLogWatcher");

            logWatcher = new PathChildrenCache(curator, absolutePath(LOG_PATH),
                                               true, false, logWatcherExecutor);
            logWatcher.getListenable().addListener(this, MoreExecutors.directExecutor());
            logWatcher.start();

            // Start the leader selection.
            oldLogRemover = new OldLogRemover();
            leaderSelectorExecutor = Executors.newSingleThreadExecutor(
                    new DefaultThreadFactory("zookeeper-leader-selector", true));
            ExecutorServiceMetrics.monitor(meterRegistry, leaderSelectorExecutor,
                                           "zkLeaderSelector");

            leaderSelector = new LeaderSelector(curator, absolutePath(LEADER_PATH),
                                                leaderSelectorExecutor, oldLogRemover);
            leaderSelector.start();

            // Start the delegate.
            delegate.start();

            // Get the command executor threads ready.
            final ThreadPoolExecutor executor = new ThreadPoolExecutor(
                    cfg.numWorkers(), cfg.numWorkers(),
                    60, TimeUnit.SECONDS, new LinkedTransferQueue<>(),
                    new DefaultThreadFactory("zookeeper-command-executor", true));
            executor.allowCoreThreadTimeOut(true);
            ExecutorServiceMetrics.monitor(meterRegistry, executor,
                                           "zkCommandExecutor");
            this.executor = executor;
        } catch (InterruptedException | ReplicationException e) {
            throw e;
        } catch (Exception e) {
            throw new ReplicationException(e);
        }
    }

    private EmbeddedZooKeeper startZooKeeper() throws Exception {
        logger.info("Starting the ZooKeeper peer ({}) ..", cfg.serverId());
        EmbeddedZooKeeper peer = null;
        boolean success = false;
        try {
            final Properties zkProps = new Properties();

            // Set the properties.
            copyZkProperty(zkProps, "initLimit", "5");
            copyZkProperty(zkProps, "syncLimit", "10");
            copyZkProperty(zkProps, "tickTime", "3000");
            copyZkProperty(zkProps, "syncEnabled", "true");
            copyZkProperty(zkProps, "autopurge.snapRetainCount", "3");
            copyZkProperty(zkProps, "autopurge.purgeInterval", "1");

            // Set the properties that must be set in System properties.
            System.setProperty("zookeeper.fsync.warningthresholdms",
                               cfg.additionalProperties().getOrDefault("fsync.warningthresholdms", "1000"));

            // Set the data directories.
            zkProps.setProperty("dataDir", zkDataDir.getPath());
            zkProps.setProperty("dataLogDir", zkLogDir.getPath());
            zkDataDir.mkdirs();
            zkLogDir.mkdirs();

            // Generate the myid file in.
            try (FileOutputStream out = new FileOutputStream(new File(zkDataDir, "myid"))) {
                out.write((cfg.serverId() + "\n").getBytes(StandardCharsets.US_ASCII));
            }

            // Generate the jaas.conf and configure system properties to enable SASL authentication
            // for server, client, quorum server and quorum learner.
            final File jaasConfFile = new File(zkDataDir, "jaas.conf");
            try (FileOutputStream out = new FileOutputStream(jaasConfFile)) {
                final StringBuilder buf = new StringBuilder();
                final String newline = System.lineSeparator();
                final String escapedSecret = jaasValueEscaper.escape(cfg.secret());
                ImmutableList.of("Server", EmbeddedZooKeeper.SASL_SERVER_LOGIN_CONTEXT).forEach(name -> {
                    buf.append(name).append(" {").append(newline);
                    buf.append(DigestLoginModule.class.getName()).append(" required").append(newline);
                    buf.append("user_super=\"").append(escapedSecret).append("\";").append(newline);
                    buf.append("};").append(newline);
                });
                ImmutableList.of("Client", EmbeddedZooKeeper.SASL_LEARNER_LOGIN_CONTEXT).forEach(name -> {
                    buf.append(name).append(" {").append(newline);
                    buf.append(DigestLoginModule.class.getName()).append(" required").append(newline);
                    buf.append("username=\"super\"").append(newline);
                    buf.append("password=\"").append(escapedSecret).append("\";").append(newline);
                    buf.append("};").append(newline);
                });
                out.write(buf.toString().getBytes());
            }
            System.setProperty("java.security.auth.login.config", jaasConfFile.getAbsolutePath());
            System.setProperty("zookeeper.authProvider.1", SASLAuthenticationProvider.class.getName());

            // Set the client port, which is unused.
            zkProps.setProperty("clientPort", String.valueOf(cfg.serverAddress().clientPort()));

            // Add replicas.
            cfg.servers().forEach((id, addr) -> {
                zkProps.setProperty(
                        "server." + id,
                        addr.host() + ':' + addr.quorumPort() + ':' + addr.electionPort() + ":participant");
            });

            // Disable Jetty-based admin server.
            zkProps.setProperty("admin.enableServer", "false");

            zkConfFile.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(zkConfFile)) {
                zkProps.store(out, null);
            }

            final QuorumPeerConfig zkCfg = new QuorumPeerConfig();
            zkCfg.parse(zkConfFile.getPath());

            peer = new EmbeddedZooKeeper(zkCfg, meterRegistry);
            peer.start();

            // Wait until the ZooKeeper joins the cluster.
            for (;;) {
                final ServerState state = peer.getPeerState();
                if (state == ServerState.FOLLOWING || state == ServerState.LEADING) {
                    break;
                }

                if (isStopping()) {
                    throw new InterruptedException("Stop requested before joining the cluster");
                }

                logger.info("Waiting for the ZooKeeper peer ({}) to join the cluster ..", peer.getId());
                Thread.sleep(1000);
            }

            if (peer.getId() == peer.getCurrentVote().getId()) {
                logger.info("The ZooKeeper peer ({}) has joined the cluster as a leader.", peer.getId());
            } else {
                logger.info("The ZooKeeper peer ({}) has joined the cluster, following {}.",
                            peer.getId(), peer.getCurrentVote().getId());
            }

            success = true;
            return peer;
        } finally {
            if (!success && peer != null) {
                try {
                    peer.shutdown();
                } catch (Exception e) {
                    logger.warn("Failed to shutdown the failed ZooKeeper peer: {}", e.getMessage(), e);
                }
            }
        }
    }

    private void copyZkProperty(Properties zkProps, String key, String defaultValue) {
        zkProps.setProperty(key, cfg.additionalProperties().getOrDefault(key, defaultValue));
    }

    private void stopLater() {
        // Stop from an other thread so that it does not get stuck
        // when this method runs on an executor thread.
        ForkJoinPool.commonPool().execute(this::stop);
    }

    @Override
    protected void doStop(@Nullable Runnable onReleaseLeadership) throws Exception {
        listenerInfo = null;
        logger.info("Stopping the worker threads");
        boolean interrupted = shutdown(executor);
        logger.info("Stopped the worker threads");

        try {
            logger.info("Stopping the delegate command executor");
            delegate.stop();
            logger.info("Stopped the delegate command executor");
        } catch (Exception e) {
            logger.warn("Failed to stop the delegate command executor {}: {}", delegate, e.getMessage(), e);
        } finally {
            retryPolicy = RETRY_POLICY_NEVER;
            try {
                if (leaderSelector != null) {
                    logger.info("Closing the leader selector");
                    leaderSelector.close();
                    interrupted |= shutdown(leaderSelectorExecutor);
                    logger.info("Closed the leader selector");
                }
            } catch (Exception e) {
                logger.warn("Failed to close the leader selector: {}", e.getMessage(), e);
            } finally {
                try {
                    if (logWatcher != null) {
                        logger.info("Closing the log watcher");
                        logWatcher.close();
                        interrupted |= shutdown(logWatcherExecutor);
                        logger.info("Closed the log watcher");
                    }
                } catch (Exception e) {
                    logger.warn("Failed to close the log watcher: {}", e.getMessage(), e);
                } finally {
                    try {
                        if (curator != null) {
                            logger.info("Closing the Curator framework");
                            curator.close();
                            logger.info("Closed the Curator framework");
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to close the Curator framework: {}", e.getMessage(), e);
                    } finally {
                        try {
                            if (quorumPeer != null) {
                                final long peerId = quorumPeer.getId();
                                logger.info("Shutting down the ZooKeeper peer ({})", peerId);
                                quorumPeer.shutdown();
                                logger.info("Shut down the ZooKeeper peer ({})", peerId);
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to shut down the ZooKeeper peer: {}", e.getMessage(), e);
                        } finally {
                            if (interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean shutdown(@Nullable ExecutorService executor) {
        if (executor == null) {
            return false;
        }

        boolean interrupted = false;
        while (!executor.isTerminated()) {
            executor.shutdown();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // Interrupt later.
                interrupted = true;
            }
        }
        return interrupted;
    }

    private long getLastReplayedRevision() throws Exception {
        final FileInputStream fis;
        try {
            fis = new FileInputStream(revisionFile);
        } catch (FileNotFoundException ignored) {
            return -1;
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(fis))) {
            final String l = br.readLine();
            if (l == null) {
                return -1;
            }
            return Long.parseLong(l.trim());
        }
    }

    private void updateLastReplayedRevision(long lastReplayedRevision) throws Exception {
        boolean success = false;
        try (FileOutputStream fos = new FileOutputStream(revisionFile)) {
            fos.write(String.valueOf(lastReplayedRevision).getBytes(StandardCharsets.UTF_8));
            success = true;
        } finally {
            if (success) {
                logger.info("Updated lastReplayedRevision to: {}", lastReplayedRevision);
            } else {
                logger.error("Failed to update lastReplayedRevision to: {}", lastReplayedRevision);
            }
        }
    }

    private synchronized void replayLogs(long targetRevision) {
        final ListenerInfo info = listenerInfo;
        if (info == null) {
            return;
        }

        if (targetRevision <= info.lastReplayedRevision) {
            return;
        }

        try {
            for (;;) {
                final long nextRevision = info.lastReplayedRevision + 1;
                final Optional<ReplicationLog<?>> log = loadLog(nextRevision, true);
                if (log.isPresent()) {
                    final ReplicationLog<?> l = log.get();
                    final Command<?> command = l.command();
                    final Object expectedResult = l.result();
                    final Object actualResult = delegate.execute(command).get();

                    if (!Objects.equals(expectedResult, actualResult)) {
                        throw new ReplicationException(
                                "mismatching replay result at revision " + info.lastReplayedRevision +
                                ": " + actualResult + " (expected: " + expectedResult +
                                ", command: " + command + ')');
                    }
                } else {
                    // same replicaId. skip
                }

                info.lastReplayedRevision = nextRevision;
                updateLastReplayedRevision(nextRevision);
                if (nextRevision == targetRevision) {
                    break;
                }
            }
        } catch (Throwable t) {
            logger.error("Failed to replay a log at revision {}; entering read-only mode",
                         info.lastReplayedRevision, t);
            stopLater();

            if (t instanceof ReplicationException) {
                throw (ReplicationException) t;
            }
            throw new ReplicationException("failed to replay a log at revision " +
                                           info.lastReplayedRevision, t);
        }
    }

    @Override
    public void childEvent(CuratorFramework unused, PathChildrenCacheEvent event) throws Exception {
        if (event.getType() != PathChildrenCacheEvent.Type.CHILD_ADDED) {
            return;
        }

        final long lastKnownRevision = revisionFromPath(event.getData().getPath());
        try {
            replayLogs(lastKnownRevision);
        } catch (ReplicationException ignored) {
            // replayLogs() logs and handles the exception already, so we just bail out here.
            return;
        }

        oldLogRemover.touch();
    }

    @FunctionalInterface
    private interface SafeLock extends AutoCloseable {
        @Override
        void close();
    }

    private final ConcurrentMap<String, InterProcessMutex> mutexMap = new ConcurrentHashMap<>();

    private SafeLock safeLock(String executionPath) {
        final InterProcessMutex mtx = mutexMap.computeIfAbsent(
                executionPath, k -> new InterProcessMutex(curator, absolutePath(LOCK_PATH, executionPath)));

        try {
            mtx.acquire();
        } catch (Exception e) {
            logger.error("Failed to acquire a lock for {}; entering read-only mode", executionPath, e);
            stopLater();
            throw new ReplicationException("failed to acquire a lock for " + executionPath, e);
        }

        return () -> {
            try {
                mtx.release();
            } catch (Exception ignored) {
                // Ignore.
            }
        };
    }

    @VisibleForTesting
    static String path(String... pathElements) {
        final StringBuilder sb = new StringBuilder();
        for (String path : pathElements) {
            if (path.startsWith("/")) { //remove starting "/"
                path = path.substring(1);
            }

            if (path.endsWith("/")) { //remove trailing "/"
                path = path.substring(0, path.length() - 1);
            }
            if (!path.isEmpty()) {
                sb.append('/');
                sb.append(path);
            }
        }
        return sb.toString();
    }

    private static String absolutePath(String... pathElements) {
        if (pathElements.length == 0) {
            return PATH_PREFIX;
        }
        return path(PATH_PREFIX, path(pathElements));
    }

    private static class LogMeta {

        private final int replicaId;
        private final long timestamp;
        private final int size;
        private final List<Long> blocks = new ArrayList<>();

        @JsonCreator
        LogMeta(@JsonProperty(value = "replicaId", required = true) int replicaId,
                @JsonProperty(value = "timestamp", defaultValue = "0") long timestamp,
                @JsonProperty("size") int size) {
            this.replicaId = replicaId;
            this.timestamp = timestamp;
            this.size = size;
        }

        @JsonProperty
        int replicaId() {
            return replicaId;
        }

        @JsonProperty
        long timestamp() {
            return timestamp;
        }

        @JsonProperty
        int size() {
            return size;
        }

        @JsonProperty
        List<Long> blocks() {
            return Collections.unmodifiableList(blocks);
        }

        public void appendBlock(long blockId) {
            blocks.add(blockId);
        }
    }

    private long storeLog(ReplicationLog<?> log) {
        try {
            final byte[] bytes = Jackson.writeValueAsBytes(log);
            assert bytes.length > 0;

            final LogMeta logMeta = new LogMeta(log.replicaId(), System.currentTimeMillis(), bytes.length);

            final int count = (bytes.length + MAX_BYTES - 1) / MAX_BYTES;
            for (int i = 0; i < count; ++i) {
                final int start = i * MAX_BYTES;
                final int end = Math.min((i + 1) * MAX_BYTES, bytes.length);
                final byte[] b = Arrays.copyOfRange(bytes, start, end);
                final String blockPath = curator.create()
                                                .withMode(CreateMode.PERSISTENT_SEQUENTIAL)
                                                .forPath(absolutePath(LOG_BLOCK_PATH) + '/', b);
                final long blockId = revisionFromPath(blockPath);
                logMeta.appendBlock(blockId);
            }

            final String logPath =
                    curator.create().withMode(CreateMode.PERSISTENT_SEQUENTIAL)
                           .forPath(absolutePath(LOG_PATH) + '/', Jackson.writeValueAsBytes(logMeta));

            return revisionFromPath(logPath);
        } catch (Exception e) {
            logger.error("Failed to store a log; entering read-only mode: {}", log, e);
            stopLater();
            throw new ReplicationException("failed to store a log: " + log, e);
        }
    }

    @VisibleForTesting
    Optional<ReplicationLog<?>> loadLog(long revision, boolean skipIfSameReplica) {
        try {
            createParentNodes();

            final String logPath = absolutePath(LOG_PATH) + '/' + pathFromRevision(revision);

            final LogMeta logMeta = Jackson.readValue(curator.getData().forPath(logPath), LogMeta.class);

            if (skipIfSameReplica && replicaId() == logMeta.replicaId()) {
                return Optional.empty();
            }

            final byte[] bytes = new byte[logMeta.size()];
            int offset = 0;
            for (long blockId : logMeta.blocks()) {
                final String blockPath = absolutePath(LOG_BLOCK_PATH) + '/' + pathFromRevision(blockId);
                final byte[] b = curator.getData().forPath(blockPath);
                System.arraycopy(b, 0, bytes, offset, b.length);
                offset += b.length;
            }
            assert logMeta.size() == offset;

            final ReplicationLog<?> log = Jackson.readValue(bytes, ReplicationLog.class);
            return Optional.of(log);
        } catch (Exception e) {
            logger.error("Failed to load a log at revision {}; entering read-only mode", revision, e);
            stopLater();
            throw new ReplicationException("failed to load a log at revision " + revision, e);
        }
    }

    private static long revisionFromPath(String path) {
        final String[] s = path.split("/");
        return Long.parseLong(s[s.length - 1]);
    }

    private static String pathFromRevision(long revision) {
        return String.format("%010d", revision);
    }

    // Ensure that all logs are replayed, any other logs can not be added before end of this function.
    @Override
    protected <T> CompletableFuture<T> doExecute(Command<T> command) throws Exception {
        final CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(blockingExecute(command));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private <T> T blockingExecute(Command<T> command) throws Exception {
        createParentNodes();

        try (SafeLock ignored = safeLock(command.executionPath())) {

            // NB: We are sure no other replicas will append the conflicting logs (the commands with the
            //     same execution path) while we hold the lock for the command's execution path.
            //
            //     Other replicas may still append the logs with different execution paths, because, by design,
            //     two commands never conflict with each other if they have different execution paths.

            final List<String> recentRevisions = curator.getChildren().forPath(absolutePath(LOG_PATH));
            if (!recentRevisions.isEmpty()) {
                final long lastRevision = recentRevisions.stream().mapToLong(Long::parseLong).max().getAsLong();
                replayLogs(lastRevision);
            }

            final T result = delegate.execute(command).get();
            final ReplicationLog<T> log = new ReplicationLog<>(replicaId(), command, result);

            // Store the command execution log to ZooKeeper.
            final long revision = storeLog(log);

            logger.debug("logging OK. revision = {}, log = {}", revision, log);
            return result;
        }
    }

    private void createParentNodes() throws Exception {
        if (createdParentNodes) {
            return;
        }

        // Create the zkPath if it does not exist.
        createZkPathIfMissing(absolutePath());
        createZkPathIfMissing(absolutePath(LOG_PATH));
        createZkPathIfMissing(absolutePath(LOG_BLOCK_PATH));
        createZkPathIfMissing(absolutePath(LOCK_PATH));

        createdParentNodes = true;
    }

    private void createZkPathIfMissing(String zkPath) throws Exception {
        try {
            curator.create().forPath(zkPath);
        } catch (KeeperException.NodeExistsException ignored) {
            // Ignore.
        }
    }
}
