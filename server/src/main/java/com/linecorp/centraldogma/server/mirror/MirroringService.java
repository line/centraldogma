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

package com.linecorp.centraldogma.server.mirror;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cronutils.model.time.ExecutionTime;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.spotify.futures.FuturesExtra;

import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.project.Project;
import com.linecorp.centraldogma.server.project.ProjectManager;

import io.netty.util.concurrent.DefaultThreadFactory;

public final class MirroringService {

    private static final Logger logger = LoggerFactory.getLogger(MirroringService.class);

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

    private ZonedDateTime lastRunTime;

    public MirroringService(File workDir, ProjectManager projectManager,
                            int numThreads, int maxNumFilesPerMirror, long maxNumBytesPerMirror) {

        this.workDir = requireNonNull(workDir, "workDir");
        this.projectManager = requireNonNull(projectManager, "projectManager");

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

        scheduler = MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor(
                new DefaultThreadFactory("mirroring-scheduler", true)));

        worker = MoreExecutors.listeningDecorator(
                new ThreadPoolExecutor(0, numThreads, 1, TimeUnit.MINUTES, new SynchronousQueue<>(),
                                       new DefaultThreadFactory("mirroring-worker", true)));

        final ListenableScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                this::schedulePendingMirrors,
                TICK.getSeconds(), TICK.getSeconds(), TimeUnit.SECONDS);

        FuturesExtra.addFailureCallback(
                future,
                cause -> logger.error("Git-to-CD mirroring scheduler stopped due to an unexpected exception:",
                                      cause));
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
        if (lastRunTime == null) {
            lastRunTime = now.minus(TICK);
        }

        final ZonedDateTime currentLastRunTime = lastRunTime;
        lastRunTime = now;

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
                          final ExecutionTime execTime = ExecutionTime.forCron(m.schedule());
                          return execTime.nextExecution(currentLastRunTime).compareTo(now) < 0;
                      })
                      .forEach(m -> {
                          final ListenableFuture<?> future = worker.submit(() -> run(m, true));
                          FuturesExtra.addFailureCallback(
                                  future,
                                  cause -> logger.warn("Unexpected Git-to-CD mirroring failure: {}", m, cause));
                      });
    }

    public void runOnce() {
        if (commandExecutor == null) {
            return;
        }

        projectManager.list().values()
                      .forEach(p -> p.metaRepo().mirrors()
                                     .forEach(m -> run(m, false)));
    }

    private void run(Mirror m, boolean logOnFailure) {
        logger.info("Mirroring: {}", m);
        try {
            m.mirror(workDir, commandExecutor, maxNumFilesPerMirror, maxNumBytesPerMirror);
        } catch (Exception e) {
            if (logOnFailure) {
                logger.warn("Unexpected exception while mirroring:", e);
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
