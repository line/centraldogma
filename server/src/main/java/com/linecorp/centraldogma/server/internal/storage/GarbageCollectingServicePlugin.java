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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.centraldogma.server.plugin.Plugin;
import com.linecorp.centraldogma.server.plugin.PluginContext;
import com.linecorp.centraldogma.server.plugin.PluginTarget;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;
import com.linecorp.centraldogma.server.storage.repository.Repository;

import io.netty.util.concurrent.DefaultThreadFactory;

public final class GarbageCollectingServicePlugin implements Plugin {

    private static final Logger logger = LoggerFactory.getLogger(GarbageCollectingServicePlugin.class);

    @Nullable
    private ScheduledExecutorService gcWorker;
    @Nullable
    private ListenableScheduledFuture<?> scheduledFuture;

    private volatile boolean stopping;

    @Override
    public PluginTarget target() {
        return PluginTarget.ALL_REPLICAS;
    }

    @Override
    public synchronized CompletionStage<Void> start(PluginContext context) {
        requireNonNull(context, "context");

        gcWorker = Executors.newSingleThreadScheduledExecutor(
                new DefaultThreadFactory("repository-gc-worker", true));
        final ListeningScheduledExecutorService scheduler = MoreExecutors.listeningDecorator(gcWorker);

        // Run gc every day.
        // TODO(ikhoon): Need to configure gc interval?
        scheduledFuture = scheduler.scheduleAtFixedRate(() -> gc(context), 0, 1, TimeUnit.DAYS);

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

        return CompletableFuture.completedFuture(null);
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
    static void gc(PluginContext context) {
        final ProjectManager pm = context.projectManager();
        final Stopwatch stopwatch = Stopwatch.createUnstarted();
        for (Project project : pm.list().values()) {
            for (Repository repo : project.repos().list().values()) {
                try {
                    logger.info("Starting repository gc on {}/{} ..", project.name(), repo.name());
                    stopwatch.reset();
                    repo.gc();
                    final long elapsedNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
                    logger.info("Finished repository gc on {}/{} - took {}", project.name(), repo.name(),
                                TextFormatter.elapsed(elapsedNanos));
                } catch (Exception e) {
                    logger.warn("Failed to run repository gc on {}/{}", project.name(), repo.name(), e);
                }
            }
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("target", target())
                          .add("scheduledFuture", scheduledFuture)
                          .toString();
    }
}
