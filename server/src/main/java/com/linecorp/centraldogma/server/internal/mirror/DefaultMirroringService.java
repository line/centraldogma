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

package com.linecorp.centraldogma.server.internal.mirror;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.centraldogma.server.MirrorException;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.storage.project.Project;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.netty.util.concurrent.DefaultThreadFactory;

public final class DefaultMirroringService implements MirroringService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultMirroringService.class);

    /**
     * How often to check the mirroring schedules. i.e. every second.
     */
    private static final Duration TICK = Duration.ofSeconds(1);

    private final File workDir;
    private final ProjectManager projectManager;
    private final int numThreads;
    private final int maxNumFilesPerMirror;
    private final long maxNumBytesPerMirror;

    private volatile CommandExecutor commandExecutor;
    private volatile ListeningScheduledExecutorService scheduler;
    private volatile ListeningExecutorService worker;

    private ZonedDateTime lastExecutionTime;
    private final MeterRegistry meterRegistry;

    DefaultMirroringService(File workDir, ProjectManager projectManager, MeterRegistry meterRegistry,
                            int numThreads, int maxNumFilesPerMirror, long maxNumBytesPerMirror) {

        this.workDir = requireNonNull(workDir, "workDir");
        this.projectManager = requireNonNull(projectManager, "projectManager");
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");

        checkArgument(numThreads > 0, "numThreads: %s (expected: > 0)", numThreads);
        checkArgument(maxNumFilesPerMirror > 0,
                      "maxNumFilesPerMirror: %s (expected: > 0)", maxNumFilesPerMirror);
        checkArgument(maxNumBytesPerMirror > 0,
                      "maxNumBytesPerMirror: %s (expected: > 0)", maxNumBytesPerMirror);
        this.numThreads = numThreads;
        this.maxNumFilesPerMirror = maxNumFilesPerMirror;
        this.maxNumBytesPerMirror = maxNumBytesPerMirror;
    }

    public boolean isStarted() {
        return scheduler != null;
    }

    public synchronized void start(CommandExecutor commandExecutor) {
        if (isStarted()) {
            return;
        }

        this.commandExecutor = requireNonNull(commandExecutor, "commandExecutor");

        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(
                new DefaultThreadFactory("mirroring-scheduler", true));
        executorService = ExecutorServiceMetrics.monitor(meterRegistry, executorService,
                                                         "mirroringScheduler");
        scheduler = MoreExecutors.listeningDecorator(executorService);

        // Use SynchronousQueue to prevent the work queue from growing infinitely
        // when the workers cannot handle the mirroring tasks fast enough.
        final SynchronousQueue<Runnable> workQueue = new SynchronousQueue<>();
        worker = MoreExecutors.listeningDecorator(new ThreadPoolExecutor(
                0, numThreads, 90, TimeUnit.SECONDS, workQueue,
                new DefaultThreadFactory("mirroring-worker", true),
                (rejectedTask, executor) -> {
                    // We do not want the mirroring tasks to be rejected.
                    // Just wait until a worker thread takes it.
                    try {
                        workQueue.put(rejectedTask);
                    } catch (InterruptedException e) {
                        // Propagate the interrupt to the scheduler.
                        Thread.currentThread().interrupt();
                    }
                }));

        final ListenableScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                this::schedulePendingMirrors,
                TICK.getSeconds(), TICK.getSeconds(), TimeUnit.SECONDS);

        Futures.addCallback(future, new FutureCallback<Object>() {
            @Override
            public void onSuccess(@Nullable Object result) {}

            @Override
            public void onFailure(Throwable cause) {
                logger.error("Git mirroring scheduler stopped due to an unexpected exception:", cause);
            }
        }, MoreExecutors.directExecutor());
    }

    public synchronized void stop() {
        final ExecutorService scheduler = this.scheduler;
        final ExecutorService worker = this.worker;

        try {
            final boolean interrupted = terminate(scheduler) || terminate(worker);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            this.scheduler = null;
            this.worker = null;
        }
    }

    private static boolean terminate(ExecutorService executor) {
        if (executor == null) {
            return false;
        }

        boolean interrupted = false;
        for (;;) {
            executor.shutdownNow();
            try {
                if (executor.awaitTermination(1, TimeUnit.MINUTES)) {
                    break;
                }
            } catch (InterruptedException e) {
                // Propagate later.
                interrupted = true;
            }
        }

        return interrupted;
    }

    private void schedulePendingMirrors() {
        final ZonedDateTime now = ZonedDateTime.now();
        if (lastExecutionTime == null) {
            lastExecutionTime = now.minus(TICK);
        }

        final ZonedDateTime currentLastExecutionTime = lastExecutionTime;
        lastExecutionTime = now;

        projectManager.list().values().stream()
                      .map(Project::metaRepo)
                      .flatMap(r -> {
                          try {
                              return r.mirrors().stream();
                          } catch (Exception e) {
                              logger.warn("Failed to load the mirror list from: {}", r.parent().name(), e);
                              return Stream.empty();
                          }
                      })
                      .filter(m -> {
                          try {
                              return m.nextExecutionTime(currentLastExecutionTime).compareTo(now) < 0;
                          } catch (Exception e) {
                              logger.warn("Failed to calculate the next execution time of: {}", m, e);
                              return false;
                          }
                      })
                      .forEach(m -> {
                          final ListenableFuture<?> future = worker.submit(() -> run(m, true));
                          Futures.addCallback(future, new FutureCallback<Object>() {
                              @Override
                              public void onSuccess(@Nullable Object result) {}

                              @Override
                              public void onFailure(Throwable cause) {
                                  logger.warn("Unexpected Git mirroring failure: {}", m, cause);
                              }
                          }, MoreExecutors.directExecutor());
                      });
    }

    @Override
    public CompletableFuture<Void> mirror() {
        if (commandExecutor == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(
                () -> projectManager.list().values()
                                    .forEach(p -> p.metaRepo().mirrors()
                                                   .forEach(m -> run(m, false))),
                worker);
    }

    private void run(Mirror m, boolean logOnFailure) {
        logger.info("Mirroring: {}", m);
        try {
            new MirroringTask(m, meterRegistry)
                    .run(workDir, commandExecutor, maxNumFilesPerMirror, maxNumBytesPerMirror);
        } catch (Exception e) {
            if (logOnFailure) {
                logger.warn("Unexpected exception while mirroring: {}", m, e);
            } else {
                if (e instanceof MirrorException) {
                    throw (MirrorException) e;
                } else {
                    throw new MirrorException(e);
                }
            }
        }
    }
}
