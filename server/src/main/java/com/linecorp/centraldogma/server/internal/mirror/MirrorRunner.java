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

package com.linecorp.centraldogma.server.internal.mirror;

import static com.linecorp.centraldogma.server.internal.ExecutorServiceUtil.terminate;

import java.io.File;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.centraldogma.common.MirrorAccessException;
import com.linecorp.centraldogma.common.MirrorException;
import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.internal.storage.project.ProjectApiManager;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.mirror.MirrorAccessController;
import com.linecorp.centraldogma.server.mirror.MirrorListener;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirrorTask;
import com.linecorp.centraldogma.server.mirror.MirroringServicePluginConfig;
import com.linecorp.centraldogma.server.storage.repository.MetaRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.netty.util.concurrent.DefaultThreadFactory;

public final class MirrorRunner implements SafeCloseable {

    private final ProjectApiManager projectApiManager;
    private final CommandExecutor commandExecutor;
    private final File workDir;
    private final MirroringServicePluginConfig mirrorConfig;
    private final ExecutorService worker;

    private final Map<MirrorKey, CompletableFuture<MirrorResult>> inflightRequests = new ConcurrentHashMap<>();
    @Nullable
    private final String currentZone;
    private final MirrorAccessController mirrorAccessController;

    public MirrorRunner(ProjectApiManager projectApiManager, CommandExecutor commandExecutor,
                        CentralDogmaConfig cfg, MeterRegistry meterRegistry,
                        MirrorAccessController mirrorAccessController) {
        this.projectApiManager = projectApiManager;
        this.commandExecutor = commandExecutor;
        // TODO(ikhoon): Periodically clean up stale repositories.
        workDir = new File(cfg.dataDir(), "_mirrors_manual");
        MirroringServicePluginConfig mirrorConfig =
                (MirroringServicePluginConfig) cfg.pluginConfigMap()
                                                  .get(MirroringServicePluginConfig.class);
        if (mirrorConfig == null) {
            mirrorConfig = MirroringServicePluginConfig.INSTANCE;
        }
        this.mirrorConfig = mirrorConfig;
        if (cfg.zone() != null) {
            currentZone = cfg.zone().currentZone();
        } else {
            currentZone = null;
        }
        this.mirrorAccessController = mirrorAccessController;

        final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
                0, mirrorConfig.numMirroringThreads(),
                // TODO(minwoox): Use LinkedTransferQueue when we upgrade to JDK 21.
                60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                new DefaultThreadFactory("mirror-api-worker", true));
        threadPoolExecutor.allowCoreThreadTimeOut(true);
        worker = ExecutorServiceMetrics.monitor(meterRegistry, threadPoolExecutor,
                                                "mirrorApiWorker");
    }

    public CompletableFuture<MirrorResult> run(String projectName, String mirrorId, User user) {
        // If there is an inflight request, return it to avoid running the same mirror task multiple times.
        return inflightRequests.computeIfAbsent(new MirrorKey(projectName, mirrorId), key -> run(key, user));
    }

    private CompletableFuture<MirrorResult> run(MirrorKey mirrorKey, User user) {
        try {
            final CompletableFuture<MirrorResult> future =
                    metaRepo(mirrorKey.projectName).mirror(mirrorKey.mirrorId).thenCompose(mirror -> {
                        if (!mirror.enabled()) {
                            throw new MirrorException("The mirror is disabled: " +
                                                      mirrorKey.projectName + '/' + mirrorKey.mirrorId);
                        }

                        return mirrorAccessController.isAllowed(mirror).thenApplyAsync(allowed -> {
                            if (!allowed) {
                                throw new MirrorAccessException(
                                        "The mirroring from " + mirror.remoteRepoUri() + " is not allowed: " +
                                        mirrorKey.projectName + '/' + mirrorKey.mirrorId);
                            }

                            final String zone = mirror.zone();
                            if (zone != null && !zone.equals(currentZone)) {
                                throw new MirrorException(
                                        "The mirror is not in the current zone: " + currentZone);
                            }
                            final MirrorTask mirrorTask = new MirrorTask(mirror, user, Instant.now(),
                                                                         currentZone, false);
                            final MirrorListener listener = MirrorSchedulingService.mirrorListener();
                            listener.onStart(mirrorTask);
                            try {
                                final MirrorResult mirrorResult =
                                        mirror.mirror(workDir, commandExecutor,
                                                      mirrorConfig.maxNumFilesPerMirror(),
                                                      mirrorConfig.maxNumBytesPerMirror(),
                                                      mirrorTask.triggeredTime());
                                listener.onComplete(mirrorTask, mirrorResult);
                                return mirrorResult;
                            } catch (Exception e) {
                                listener.onError(mirrorTask, e);
                                throw e;
                            }
                        }, worker);
                    });
            // Remove the inflight request when the mirror task is done.
            future.handleAsync((unused0, unused1) -> inflightRequests.remove(mirrorKey), worker);
            return future;
        } catch (Throwable e) {
            inflightRequests.remove(mirrorKey);
            throw e;
        }
    }

    private MetaRepository metaRepo(String projectName) {
        // Assume that internal projects does not have mirrors.
        return projectApiManager.getProject(projectName, null).metaRepo();
    }

    @Override
    public void close() {
        final boolean interrupted = terminate(worker);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        inflightRequests.clear();
    }

    private static final class MirrorKey {
        private final String projectName;
        private final String mirrorId;

        private MirrorKey(String projectName, String mirrorId) {
            this.projectName = projectName;
            this.mirrorId = mirrorId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MirrorKey)) {
                return false;
            }
            final MirrorKey mirrorKey = (MirrorKey) o;
            return projectName.equals(mirrorKey.projectName) &&
                   mirrorId.equals(mirrorKey.mirrorId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectName, mirrorId);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("projectName", projectName)
                              .add("mirrorId", mirrorId)
                              .toString();
        }
    }
}
