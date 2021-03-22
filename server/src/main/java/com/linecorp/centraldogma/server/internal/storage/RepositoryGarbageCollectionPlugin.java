/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.centraldogma.server.internal.storage;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cronutils.model.time.ExecutionTime;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.CentralDogmaConfig;
import com.linecorp.centraldogma.server.RepositoryGarbageCollectionConfig;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.netty.util.concurrent.DefaultThreadFactory;

public final class RepositoryGarbageCollectionPlugin implements Plugin {

    private static final Logger logger =
            LoggerFactory.getLogger(RepositoryGarbageCollectionPlugin.class);

    @Nullable
    private RepositoryGarbageCollectionConfig gcConfig;
    @Nullable
    private ListeningScheduledExecutorService gcWorker;
    @Nullable
    private ListenableScheduledFuture<?> scheduledFuture;

    private volatile boolean stopping;

    @Override
    public PluginTarget target() {
        return PluginTarget.ALL_REPLICAS;
    }

    @Override
    public boolean isEnabled(CentralDogmaConfig config) {
        return config.repositoryGarbageCollection() != null;
    }

    @Override
    public synchronized CompletionStage<Void> start(PluginContext context) {
        requireNonNull(context, "context");

        initialize(context);
        scheduleGc(context);

        return CompletableFuture.completedFuture(null);
    }

    @VisibleForTesting
    void initialize(PluginContext context) {
        gcConfig = context.config().repositoryGarbageCollection();
        gcWorker = MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor(
                new DefaultThreadFactory("repository-gc-worker", true)));
    }

    private void scheduleGc(PluginContext context) {
        if (stopping) {
            return;
        }

        final Duration nextExecution = ExecutionTime.forCron(gcConfig.schedule())
                                                    .timeToNextExecution(ZonedDateTime.now());
        scheduledFuture = gcWorker.schedule(() -> gc(context), nextExecution);

        Futures.addCallback(scheduledFuture, new FutureCallback<Object>() {
            @Override
            public void onSuccess(@Nullable Object result) {}

            @Override
            public void onFailure(Throwable cause) {
                if (!stopping) {
                    logger.warn("Repository gc scheduler stopped due to an unexpected exception:", cause);
                }
            }
        }, gcWorker);
    }

    @Override
    public synchronized CompletionStage<Void> stop(PluginContext context) {
        stopping = true;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }

        try {
            if (gcWorker != null && !gcWorker.isTerminated()) {
                logger.info("Stopping the repository gc worker ..");
                boolean interruptLater = false;
                while (!gcWorker.isTerminated()) {
                    gcWorker.shutdownNow();
                    try {
                        gcWorker.awaitTermination(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        // Interrupt later.
                        interruptLater = true;
                    }
                }
                logger.info("Stopped the repository gc worker.");

                if (interruptLater) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Throwable t) {
            logger.warn("Failed to stop the repository gc worker:", t);
        }
        return CompletableFuture.completedFuture(null);
    }

    @VisibleForTesting
    void gc(PluginContext context) {
        if (stopping) {
            return;
        }

        final ProjectManager pm = context.projectManager();
        final MeterRegistry meterRegistry = context.meterRegistry();
        final Stopwatch stopwatch = Stopwatch.createUnstarted();
        for (Project project : pm.list().values()) {
            for (Repository repo : project.repos().list().values()) {
                runGc(project, repo, stopwatch, meterRegistry);
            }
        }

        scheduleGc(context);
    }

    private void runGc(Project project, Repository repo, Stopwatch stopwatch, MeterRegistry meterRegistry) {
        final String projectName = project.name();
        final String repoName = repo.name();
        try {
            if (!needsGc(repo)) {
                return;
            }

            logger.info("Starting repository gc on {}/{} ..", projectName, repoName);
            final Timer timer = MoreMeters.newTimer(meterRegistry, "plugins.gc.duration",
                                                    Tags.of(projectName, repoName));
            stopwatch.reset().start();
            repo.gc();
            final long elapsedNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
            timer.record(elapsedNanos, TimeUnit.NANOSECONDS);
            logger.info("Finished repository gc on {}/{} - took {}", projectName, repoName,
                        TextFormatter.elapsed(elapsedNanos));
        } catch (Exception e) {
            logger.warn("Failed to run repository gc on {}/{}", projectName, repoName, e);
        }
    }

    private boolean needsGc(Repository repo) {
        final Revision endRevision = repo.normalizeNow(Revision.HEAD);
        final Revision gcRevision = repo.lastGcRevision();
        final int newCommitsSinceLastGc;
        if (gcRevision == null) {
            newCommitsSinceLastGc = endRevision.major();
        } else {
            newCommitsSinceLastGc = endRevision.major() - gcRevision.major();
        }

        return newCommitsSinceLastGc >= gcConfig.minNumNewCommits();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("target", target())
                          .add("scheduledFuture", scheduledFuture)
                          .add("stopping", stopping)
                          .toString();
    }
}
