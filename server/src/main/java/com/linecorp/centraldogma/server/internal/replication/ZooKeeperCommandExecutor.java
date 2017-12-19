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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.server.internal.command.AbstractCommandExecutor;
import com.linecorp.centraldogma.server.internal.command.Command;
import com.linecorp.centraldogma.server.internal.command.CommandExecutor;

import io.netty.util.concurrent.DefaultThreadFactory;

public final class ZooKeeperCommandExecutor extends AbstractCommandExecutor
                                            implements PathChildrenCacheListener {

    public static final int DEFAULT_TIMEOUT_MILLIS = 1000;
    public static final int DEFAULT_NUM_WORKERS = 16;
    public static final int DEFAULT_MAX_LOG_COUNT = 100;
    public static final long DEFAULT_MIN_LOG_AGE_MILLIS = TimeUnit.HOURS.toMillis(1);

    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperCommandExecutor.class);
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

    private final CommandExecutor delegate;
    private final CuratorFramework curator;
    private final String zkPath; //absolute path
    private final boolean createPathIfNotExist;
    private final ExecutorService executor;
    private final PathChildrenCache logWatcher;
    private final OldLogRemover oldLogRemover;
    private final LeaderSelector leaderSelector;
    private final File revisionFile;
    private final int maxLogCount;
    private final long minLogAgeMillis;

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
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("Leader stopped due to an unexpected exception:", e);
            } finally {
                logger.info("Releasing leadership: {}", replicaId());
                if (listenerInfo.onReleaseLeadership != null) {
                    listenerInfo.onReleaseLeadership.run();
                }
            }
        }

        public synchronized void touch() {
            notify();
        }

        private void deleteLogs() throws Exception {
            final List<String> children = curator.getChildren().forPath(absolutePath(LOG_PATH));
            if (children.size() <= maxLogCount) {
                return;
            }

            final long minAllowedTimestamp = System.currentTimeMillis() - minLogAgeMillis;
            final int targetCount = children.size() - maxLogCount;
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

                    final CuratorTransactionFinal tr = curator.inTransaction().delete().forPath(logPath).and();
                    for (long blockId : meta.blocks()) {
                        String blockPath = absolutePath(LOG_BLOCK_PATH) + '/' + pathFromRevision(blockId);
                        tr.delete().forPath(blockPath).and();
                    }

                    tr.commit();
                    deleted.add(children.get(i));
                }
            } finally {
                logger.info("delete logs: {}", deleted);
            }
        }
    }

    //listener info
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

    public static class Builder {
        private String replicaId;
        private CommandExecutor delegate;
        private int numWorkers = DEFAULT_NUM_WORKERS;
        private String connectionString;
        private int timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
        private boolean createPathIfNotExist;
        private String path;
        private File revisionFile;
        private int maxLogCount = DEFAULT_MAX_LOG_COUNT;
        private long minLogAgeMillis = DEFAULT_MIN_LOG_AGE_MILLIS;

        public Builder replicaId(String replicaId) {
            this.replicaId = replicaId;
            return this;
        }

        public Builder delegate(CommandExecutor delegate) {
            this.delegate = delegate;
            return this;
        }

        public Builder numWorkers(int numWorkers) {
            if (numWorkers <= 0) {
                throw new IllegalArgumentException(
                        "numWorkers: " + numWorkers + " (expected: > 0)");
            }
            this.numWorkers = numWorkers;
            return this;
        }

        public Builder connectionString(String connectionString) {
            this.connectionString = connectionString;
            return this;
        }

        public Builder timeoutMillis(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        public Builder createPathIfNotExist(boolean b) {
            createPathIfNotExist = b;
            return this;
        }

        public Builder path(String path) {
            path = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            checkArgument(!path.isEmpty(), "ZooKeeper path must not refer to the root node.");
            this.path = path;
            return this;
        }

        public Builder revisionFile(File f) {
            revisionFile = f;
            return this;
        }

        public Builder maxLogCount(int c) {
            if (c <= 0) {
                throw new IllegalArgumentException("maxLogCount: " + maxLogCount + " (expected: > 0)");
            }
            maxLogCount = c;
            return this;
        }

        public Builder minLogAge(long minLogAge, TimeUnit unit) {
            if (minLogAge <= 0) {
                throw new IllegalArgumentException("minLogAge: " + minLogAge + " (expected: > 0)");
            }

            minLogAgeMillis = requireNonNull(unit, "unit").toMillis(minLogAge);
            return this;
        }

        public ZooKeeperCommandExecutor build() {
            requireNonNull(replicaId, "replicaId");
            requireNonNull(delegate, "delegate");
            requireNonNull(connectionString, "connectionString");
            requireNonNull(path, "path");
            requireNonNull(revisionFile, "revisionFile");

            final CuratorFramework curator = CuratorFrameworkFactory.newClient(
                    connectionString, new ExponentialBackoffRetry(timeoutMillis, 3));

            return new ZooKeeperCommandExecutor(
                    replicaId, delegate, curator, path, createPathIfNotExist,
                    revisionFile, numWorkers, maxLogCount, minLogAgeMillis);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private ZooKeeperCommandExecutor(String replicaId, CommandExecutor delegate, CuratorFramework curator,
                                     String zkPath, boolean createPathIfNotExist, File revisionFile,
                                     int numWorkers, int maxLogCount, long minLogAgeMillis) {
        super(replicaId);

        this.delegate = delegate;
        this.revisionFile = revisionFile;
        this.curator = curator;
        this.zkPath = zkPath;
        this.createPathIfNotExist = createPathIfNotExist;
        this.maxLogCount = maxLogCount;
        this.minLogAgeMillis = minLogAgeMillis;

        final ThreadPoolExecutor executor = new ThreadPoolExecutor(
                numWorkers, numWorkers,
                60, TimeUnit.SECONDS, new LinkedTransferQueue<>(),
                new DefaultThreadFactory("zookeeper-command-executor", true));
        executor.allowCoreThreadTimeOut(true);
        this.executor = executor;

        logWatcher = new PathChildrenCache(curator, absolutePath(LOG_PATH), true);
        logWatcher.getListenable().addListener(this, MoreExecutors.directExecutor());
        oldLogRemover = new OldLogRemover();
        leaderSelector = new LeaderSelector(curator, absolutePath(LEADER_PATH), oldLogRemover);
        leaderSelector.autoRequeue();
    }

    @Override
    protected void doStart(@Nullable Runnable onTakeLeadership,
                           @Nullable Runnable onReleaseLeadership) {
        try {
            // Note that we do not pass the leadership callbacks because we handle them by ourselves.
            delegate.start(null, null);

            // Get the last replayed revision.
            final long lastReplayedRevision;
            try {
                lastReplayedRevision = getLastReplayedRevision();
            } catch (Exception e) {
                throw new ReplicationException(e);
            }
            listenerInfo = new ListenerInfo(lastReplayedRevision, onTakeLeadership, onReleaseLeadership);

            // Start the Curator framework.
            curator.start();

            // Create the zkPath if it does not exist.
            if (createPathIfNotExist) {
                createZkPathIfMissing(zkPath);
                createZkPathIfMissing(zkPath + '/' + LOG_PATH);
                createZkPathIfMissing(zkPath + '/' + LOG_BLOCK_PATH);
                createZkPathIfMissing(zkPath + '/' + LOCK_PATH);
            }

            // Start the log replay.
            logWatcher.start();

            // Start the leader selection.
            leaderSelector.start();
        } catch (Exception e) {
            throw new ReplicationException(e);
        }
    }

    private void createZkPathIfMissing(String zkPath) throws Exception {
        if (curator.checkExists().forPath(zkPath) == null) {
            curator.create().forPath(zkPath);
        }
    }

    private void stopLater() {
        // Stop from an other thread so that it does not get stuck
        // when this method runs on an executor thread.
        ForkJoinPool.commonPool().execute(this::stop);
    }

    @Override
    protected void doStop() {
        boolean interruptLater = false;
        while (!executor.isTerminated()) {
            executor.shutdown();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // Interrupt later.
                interruptLater = true;
            }
        }

        try {
            leaderSelector.close();
        } catch (Exception e) {
            logger.warn("Failed to close the leader selector: {}", e.getMessage(), e);
        } finally {
            try {
                logWatcher.close();
            } catch (IOException e) {
                logger.warn("Failed to close the log watcher: {}", e.getMessage(), e);
            } finally {
                // TODO(bindung): check concurrent issue.
                // when execute below line, other thread can be running listener. that is ok.
                // but how about ensure listener end?
                listenerInfo = null;

                curator.close();

                if (interruptLater) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private long getLastReplayedRevision() throws Exception {
        final FileInputStream fis;
        try {
            fis = new FileInputStream(revisionFile);
        } catch (FileNotFoundException ignored) {
            return -1;
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(fis))) {
            String l = br.readLine();
            if (l == null) {
                return -1;
            }
            return Long.parseLong(l.trim());
        }
    }

    private void updateLastReplayedRevision(long lastReplayedRevision) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(revisionFile)) {
            fos.write(String.valueOf(lastReplayedRevision).getBytes(StandardCharsets.UTF_8));
        }
        logger.info("Update lastReplayedRevision to: {}", lastReplayedRevision);
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
                    final String originatingReplicaId = l.replicaId();
                    final Command<?> command = l.command();
                    final Object expectedResult = l.result();
                    final Object actualResult = delegate.execute(originatingReplicaId, command).join();

                    if (!Objects.equals(expectedResult, actualResult)) {
                        throw new ReplicationException(
                                "mismatching replay result: " + actualResult +
                                " (expected: " + expectedResult + ", command: " + command + ')');
                    }
                } else {
                    // same replicaId. skip
                }

                info.lastReplayedRevision = nextRevision;
                if (nextRevision == targetRevision) {
                    break;
                }
            }
        } catch (Exception e) {
            logger.warn("log replay fails. stop.", e);
            stopLater();

            if (e instanceof ReplicationException) {
                throw (ReplicationException) e;
            }
            throw new ReplicationException(e);
        }

        try {
            updateLastReplayedRevision(targetRevision);
        } catch (Exception e) {
            throw new ReplicationException(e);
        }
    }

    @Override
    public void childEvent(CuratorFramework unused, PathChildrenCacheEvent event) throws Exception {
        if (event.getType() != PathChildrenCacheEvent.Type.CHILD_ADDED) {
            return;
        }

        final long lastKnownRevision = revisionFromPath(event.getData().getPath());
        replayLogs(lastKnownRevision);
        oldLogRemover.touch();
    }

    @FunctionalInterface
    private interface SafeLock extends AutoCloseable {}

    private final ConcurrentMap<String, InterProcessMutex> mutexMap = new ConcurrentHashMap<>();

    private SafeLock safeLock(String executionPath) {
        InterProcessMutex mtx = mutexMap.computeIfAbsent(
                executionPath, k -> new InterProcessMutex(curator, absolutePath(LOCK_PATH, executionPath)));

        try {
            mtx.acquire();
        } catch (Exception e) {
            throw new ReplicationException(e);
        }

        return mtx::release;
    }

    @VisibleForTesting
    static String path(String... pathElements) {
        StringBuilder sb = new StringBuilder();
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

    private String absolutePath(String... pathElements) {
        if (pathElements.length == 0) {
            return zkPath;
        }
        return path(zkPath, path(pathElements));
    }

    private static class LogMeta {

        private final String replicaId;
        private final long timestamp;
        private final int size;
        private final List<Long> blocks = new ArrayList<>();

        @JsonCreator
        LogMeta(@JsonProperty("replicaId") String replicaId,
                @JsonProperty(value = "timestamp", defaultValue = "0") long timestamp,
                @JsonProperty("size") int size) {
            this.replicaId = replicaId;
            this.timestamp = timestamp;
            this.size = size;
        }

        @JsonProperty
        String replicaId() {
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
            byte[] bytes = Jackson.writeValueAsBytes(log);
            assert bytes.length > 0;

            LogMeta logMeta = new LogMeta(log.replicaId(), System.currentTimeMillis(), bytes.length);

            final int count = (bytes.length + MAX_BYTES - 1) / MAX_BYTES;
            for (int i = 0; i < count; ++i) {
                int start = i * MAX_BYTES;
                int end = Math.min((i + 1) * MAX_BYTES, bytes.length);
                byte[] b = Arrays.copyOfRange(bytes, start, end);
                String blockPath = curator.create()
                                          .withMode(CreateMode.PERSISTENT_SEQUENTIAL)
                                          .forPath(absolutePath(LOG_BLOCK_PATH) + '/', b);
                long blockId = revisionFromPath(blockPath);
                logMeta.appendBlock(blockId);
            }

            String logPath = curator.create().withMode(CreateMode.PERSISTENT_SEQUENTIAL)
                                    .forPath(absolutePath(LOG_PATH) + '/', Jackson.writeValueAsBytes(logMeta));

            return revisionFromPath(logPath);
        } catch (Exception e) {
            throw new ReplicationException(e);
        }
    }

    @VisibleForTesting
    Optional<ReplicationLog<?>> loadLog(long revision, boolean skipIfSameReplica) {
        try {
            String logPath = absolutePath(LOG_PATH) + '/' + pathFromRevision(revision);

            LogMeta logMeta = Jackson.readValue(curator.getData().forPath(logPath), LogMeta.class);

            if (skipIfSameReplica && Objects.equals(replicaId(), logMeta.replicaId())) {
                return Optional.empty();
            }

            byte[] bytes = new byte[logMeta.size()];
            int offset = 0;
            for (long blockId : logMeta.blocks()) {
                String blockPath = absolutePath(LOG_BLOCK_PATH) + '/' + pathFromRevision(blockId);
                byte[] b = curator.getData().forPath(blockPath);
                System.arraycopy(b, 0, bytes, offset, b.length);
                offset += b.length;
            }
            assert logMeta.size() == offset;

            final ReplicationLog<?> log = Jackson.readValue(bytes, ReplicationLog.class);
            return Optional.of(log);
        } catch (Exception e) {
            throw new ReplicationException(e);
        }
    }

    private static long revisionFromPath(String path) {
        String[] s = path.split("/");
        return Long.parseLong(s[s.length - 1]);
    }

    private static String pathFromRevision(long revision) {
        return String.format("%010d", revision);
    }

    // Ensure that all logs are replayed, any other logs can not be added before end of this function.
    @Override
    protected <T> CompletableFuture<T> doExecute(String replicaId, Command<T> command) throws Exception {
        final CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(blockingExecute(replicaId, command));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private <T> T blockingExecute(String replicaId, Command<T> command) throws Exception {
        SafeLock lock = null;
        try {
            lock = safeLock(command.executionPath());

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

            final T result = delegate.execute(replicaId, command).join();
            final ReplicationLog<T> log = new ReplicationLog<>(replicaId(), command, result);

            // Store the command execution log to ZooKeeper.
            final long revision = storeLog(log);

            logger.debug("logging OK. revision = {}, log = {}", revision, log);
            return result;
        } catch (ReplicationException e) {
            stopLater();
            throw e;
        } finally {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Exception ignored) {
                    // Ignore.
                }
            }
        }
    }
}
