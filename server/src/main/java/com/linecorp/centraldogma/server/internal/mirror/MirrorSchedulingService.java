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
import static com.linecorp.centraldogma.server.internal.ExecutorServiceUtil.terminate;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.centraldogma.common.MirrorException;
import com.linecorp.centraldogma.server.MirroringService;
import com.linecorp.centraldogma.server.ZoneConfig;
import com.linecorp.centraldogma.server.command.CommandExecutor;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.mirror.Mirror;
import com.linecorp.centraldogma.server.mirror.MirrorAccessController;
import com.linecorp.centraldogma.server.mirror.MirrorListener;
import com.linecorp.centraldogma.server.mirror.MirrorResult;
import com.linecorp.centraldogma.server.mirror.MirrorTask;
import com.linecorp.centraldogma.server.storage.project.InternalProjectInitializer;
import com.linecorp.centraldogma.server.storage.project.ProjectManager;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.netty.util.concurrent.DefaultThreadFactory;

public final class MirrorSchedulingService implements MirroringService {

    private static final Logger logger = LoggerFactory.getLogger(MirrorSchedulingService.class);

    private static final MirrorListener mirrorListener;

    static {
        final List<MirrorListener> listeners =
                ImmutableList.copyOf(ServiceLoader.load(MirrorListener.class,
                                                        DefaultMirroringServicePlugin.class.getClassLoader()));
        if (listeners.isEmpty()) {
            mirrorListener = DefaultMirrorListener.INSTANCE;
        } else {
            mirrorListener = new CompositeMirrorListener(listeners);
        }
    }

    /**
     * How often to check the mirroring schedules. i.e. every second.
     */
    private static final Duration TICK = Duration.ofSeconds(1);

    public static MirrorListener mirrorListener() {
        return mirrorListener;
    }

    private final File workDir;
    private final ProjectManager projectManager;
    private final int numThreads;
    private final int maxNumFilesPerMirror;
    private final long maxNumBytesPerMirror;
    @Nullable
    private final ZoneConfig zoneConfig;
    @Nullable
    private final String currentZone;
    private final MirrorAccessController mirrorAccessController;
    private final AtomicInteger numActiveMirrors = new AtomicInteger();

    private volatile CommandExecutor commandExecutor;
    private volatile ListeningScheduledExecutorService scheduler;
    private volatile ListeningExecutorService worker;
    private volatile boolean closing;

    private ZonedDateTime lastExecutionTime;
    private final MeterRegistry meterRegistry;
    // Used to disable in the tests.
    private final boolean runMigration;

    @VisibleForTesting
    public MirrorSchedulingService(File workDir, ProjectManager projectManager, MeterRegistry meterRegistry,
                                   int numThreads, int maxNumFilesPerMirror, long maxNumBytesPerMirror,
                                   @Nullable ZoneConfig zoneConfig, boolean runMigration,
                                   MirrorAccessController mirrorAccessController) {

        this.workDir = requireNonNull(workDir, "workDir");
        this.projectManager = requireNonNull(projectManager, "projectManager");
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        this.runMigration = runMigration;

        checkArgument(numThreads > 0, "numThreads: %s (expected: > 0)", numThreads);
        checkArgument(maxNumFilesPerMirror > 0,
                      "maxNumFilesPerMirror: %s (expected: > 0)", maxNumFilesPerMirror);
        checkArgument(maxNumBytesPerMirror > 0,
                      "maxNumBytesPerMirror: %s (expected: > 0)", maxNumBytesPerMirror);
        this.numThreads = numThreads;
        this.maxNumFilesPerMirror = maxNumFilesPerMirror;
        this.maxNumBytesPerMirror = maxNumBytesPerMirror;
        this.zoneConfig = zoneConfig;
        if (zoneConfig != null) {
            currentZone = zoneConfig.currentZone();
        } else {
            currentZone = null;
        }
        this.mirrorAccessController = mirrorAccessController;
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
                this::scheduleMirrors,
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
        closing = true;
        final int numActiveMirrors = this.numActiveMirrors.get();
        if (numActiveMirrors > 0) {
            // TODO(ikhoon): Consider adding an option for the maximum wait time.
            logger.info("Waiting for {} active mirrors to finish up to 10 seconds...", numActiveMirrors);
            for (int i = 0; i < 10; i++) {
                try {
                    if (this.numActiveMirrors.get() == 0) {
                        break;
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
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

    private void scheduleMirrors() {
        if (closing) {
            return;
        }

        final ZonedDateTime now = ZonedDateTime.now();
        if (lastExecutionTime == null) {
            lastExecutionTime = now.minus(TICK);
        }

        final ZonedDateTime currentLastExecutionTime = lastExecutionTime;
        lastExecutionTime = now;

        projectManager.list()
                      .values()
                      .forEach(project -> {
                          if (closing) {
                              return;
                          }
                          if (InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(project.name())) {
                              return;
                          }

                          final List<Mirror> mirrors;
                          try {
                              mirrors = project.metaRepo().mirrors()
                                               .get(5, TimeUnit.SECONDS);
                          } catch (TimeoutException e) {
                              logger.warn("Failed to load the mirror list within 5 seconds. project: {}",
                                          project.name(), e);
                              return;
                          } catch (Exception e) {
                              logger.warn("Failed to load the mirror list from: {}", project.name(), e);
                              return;
                          }
                          for (Mirror m : mirrors) {
                              if (m.schedule() == null) {
                                  continue;
                              }

                              if (closing) {
                                  return;
                              }

                              try {
                                  final boolean allowed = mirrorAccessController.isAllowed(m)
                                                                                .get(5, TimeUnit.SECONDS);
                                  if (!allowed) {
                                      mirrorListener.onDisallowed(m);
                                      continue;
                                  }
                              } catch (Exception e) {
                                  logger.warn("Failed to check the access control. mirror: {}",
                                              m, e);
                                  continue;
                              }

                              if (zoneConfig != null) {
                                  String pinnedZone = m.zone();
                                  if (pinnedZone == null) {
                                      // Use the first zone if the mirror does not specify a zone.
                                      pinnedZone = zoneConfig.allZones().get(0);
                                  }
                                  if (!pinnedZone.equals(currentZone)) {
                                      // Skip the mirror if it is pinned to a different zone.
                                      if (!zoneConfig.allZones().contains(pinnedZone)) {
                                          // The mirror is pinned to an invalid zone.
                                          final MirrorTask invalidMirror =
                                                  new MirrorTask(m, User.SYSTEM, Instant.now(),
                                                                 pinnedZone, true);
                                          mirrorListener.onStart(invalidMirror);
                                          mirrorListener.onError(invalidMirror, new MirrorException(
                                                  "The mirror is pinned to an unknown zone: " + pinnedZone +
                                                  " (valid zones: " + zoneConfig.allZones() + ')'));
                                      }
                                      continue;
                                  }
                              }
                              try {
                                  if (m.nextExecutionTime(currentLastExecutionTime).compareTo(now) < 0) {
                                      runAsync(new MirrorTask(m, User.SYSTEM, Instant.now(),
                                                              currentZone, true));
                                  }
                              } catch (Exception e) {
                                  logger.warn("Unexpected exception while mirroring: {}", m, e);
                              }
                          }
                      });
    }

    @Override
    public CompletableFuture<Void> mirror() {
        // Note: This method is not used in the production code.
        if (commandExecutor == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(
                () -> projectManager.list().values().forEach(p -> {
                    if (InternalProjectInitializer.INTERNAL_PROJECT_DOGMA.equals(p.name())) {
                        return;
                    }
                    try {
                        p.metaRepo().mirrors().get(5, TimeUnit.SECONDS)
                         .forEach(m -> run(new MirrorTask(m, User.SYSTEM, Instant.now(), currentZone, false)));
                    } catch (InterruptedException | TimeoutException | ExecutionException e) {
                        throw new IllegalStateException(
                                "Failed to load mirror list with in 5 seconds. project: " + p.name(), e);
                    }
                }),
                worker);
    }

    private void runAsync(MirrorTask m) {
        worker.submit(() -> run(m));
    }

    private void run(MirrorTask mirrorTask) {
        numActiveMirrors.incrementAndGet();
        if (closing) {
            numActiveMirrors.decrementAndGet();
            return;
        }

        try {
            mirrorListener.onStart(mirrorTask);
            final MirrorResult result =
                    new InstrumentedMirroringJob(mirrorTask, meterRegistry)
                            .run(workDir, commandExecutor, maxNumFilesPerMirror, maxNumBytesPerMirror);
            mirrorListener.onComplete(mirrorTask, result);
        } catch (Exception e) {
            mirrorListener.onError(mirrorTask, e);
            if (!mirrorTask.scheduled()) {
                if (e instanceof MirrorException) {
                    throw (MirrorException) e;
                } else {
                    throw new MirrorException(e);
                }
            }
        } finally {
            numActiveMirrors.decrementAndGet();
        }
    }
}
