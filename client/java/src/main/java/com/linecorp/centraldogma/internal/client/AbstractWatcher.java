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
package com.linecorp.centraldogma.internal.client;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.math.LongMath.saturatedAdd;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.client.Latest;
import com.linecorp.centraldogma.client.Watcher;
import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.ShuttingDownException;

abstract class AbstractWatcher<T> implements Watcher<T> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractWatcher.class);

    private static final long DELAY_ON_SUCCESS_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private static final long MIN_INTERVAL_MILLIS = DELAY_ON_SUCCESS_MILLIS * 2;
    private static final long MAX_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final double JITTER_RATE = 0.2;

    private static long nextDelayMillis(int numAttemptsSoFar) {
        final long nextDelayMillis;
        if (numAttemptsSoFar == 1) {
            nextDelayMillis = MIN_INTERVAL_MILLIS;
        } else {
            nextDelayMillis = Math.min(
                    saturatedMultiply(MIN_INTERVAL_MILLIS, Math.pow(2.0, numAttemptsSoFar - 1)),
                    MAX_INTERVAL_MILLIS);
        }

        final long minJitter = (long) (nextDelayMillis * (1 - JITTER_RATE));
        final long maxJitter = (long) (nextDelayMillis * (1 + JITTER_RATE));
        final long bound = maxJitter - minJitter + 1;
        final long millis = random(bound);
        return Math.max(0, saturatedAdd(minJitter, millis));
    }

    private static long saturatedMultiply(long left, double right) {
        final double result = left * right;
        return result >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) result;
    }

    private static long random(long bound) {
        assert bound > 0;
        final long mask = bound - 1;
        final Random random = ThreadLocalRandom.current();
        long result = random.nextLong();

        if ((bound & mask) == 0L) {
            // power of two
            result &= mask;
        } else { // reject over-represented candidates
            for (long u = result >>> 1; u + mask - (result = u % bound) < 0L; u = random.nextLong() >>> 1) {
                continue;
            }
        }

        return result;
    }

    private enum State {
        INIT,
        STARTED,
        STOPPED
    }

    private final CentralDogma client;
    private final ScheduledExecutorService executor;
    private final String projectName;
    private final String repositoryName;
    private final String pathPattern;

    private final List<BiConsumer<? super Revision, ? super T>> updateListeners;
    private final AtomicReference<State> state;
    private final CompletableFuture<Latest<T>> initialValueFuture;

    private volatile Latest<T> latest;
    private volatile ScheduledFuture<?> currentScheduleFuture;
    private volatile CompletableFuture<?> currentWatchFuture;

    protected AbstractWatcher(CentralDogma client, ScheduledExecutorService executor,
                              String projectName, String repositoryName, String pathPattern) {
        this.client = requireNonNull(client, "client");
        this.executor = requireNonNull(executor, "executor");
        this.projectName = requireNonNull(projectName, "projectName");
        this.repositoryName = requireNonNull(repositoryName, "repositoryName");
        this.pathPattern = requireNonNull(pathPattern, "pathPattern");

        updateListeners = new CopyOnWriteArrayList<>();
        state = new AtomicReference<>(State.INIT);
        initialValueFuture = new CompletableFuture<>();
    }

    @Override
    public CompletableFuture<Latest<T>> initialValueFuture() {
        return initialValueFuture;
    }

    @Override
    public Latest<T> latest() {
        final Latest<T> latest = this.latest;
        if (latest == null) {
            throw new IllegalStateException("value not available yet");
        }
        return latest;
    }

    /**
     * Starts to watch the file specified in the {@link Query} or the {@code pathPattern}
     * given with the constructor.
     */
    public void start() {
        if (state.compareAndSet(State.INIT, State.STARTED)) {
            scheduleWatch(0);
        }
    }

    @Override
    public void close() {
        state.set(State.STOPPED);
        if (!initialValueFuture.isDone()) {
            initialValueFuture.cancel(false);
        }

        // Cancel any scheduled operations.
        final ScheduledFuture<?> currentScheduleFuture = this.currentScheduleFuture;
        if (currentScheduleFuture != null && !currentScheduleFuture.isDone()) {
            currentScheduleFuture.cancel(false);
        }
        final CompletableFuture<?> currentWatchFuture = this.currentWatchFuture;
        if (currentWatchFuture != null && !currentWatchFuture.isDone()) {
            currentWatchFuture.cancel(false);
        }
    }

    private boolean isStopped() {
        return state.get() == State.STOPPED;
    }

    @Override
    public void watch(BiConsumer<? super Revision, ? super T> listener) {
        requireNonNull(listener, "listener");
        checkState(!isStopped(), "watcher closed");
        updateListeners.add(listener);

        if (latest != null) {
            // Perform initial notification so that the listener always gets the initial value.
            try {
                executor.execute(() -> {
                    final Latest<T> latest = this.latest;
                    listener.accept(latest.revision(), latest.value());
                });
            } catch (RejectedExecutionException e) {
                handleEventLoopShutdown(e);
            }
        }
    }

    private void scheduleWatch(int numAttemptsSoFar) {
        if (isStopped()) {
            return;
        }

        final long delay;
        if (numAttemptsSoFar == 0) {
            delay = latest != null ? DELAY_ON_SUCCESS_MILLIS : 0;
        } else {
            delay = nextDelayMillis(numAttemptsSoFar);
        }

        try {
            currentScheduleFuture = executor.schedule(() -> {
                currentScheduleFuture = null;
                doWatch(numAttemptsSoFar);
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            handleEventLoopShutdown(e);
        }
    }

    private void doWatch(int numAttemptsSoFar) {
        if (isStopped()) {
            return;
        }

        final Revision lastKnownRevision = latest != null ? latest.revision() : Revision.INIT;
        final CompletableFuture<Latest<T>> f = doWatch(client, projectName, repositoryName, lastKnownRevision);

        currentWatchFuture = f;
        f.whenComplete((result, cause) -> currentWatchFuture = null)
         .thenAccept(newLatest -> {
             if (newLatest != null) {
                 final Latest<T> oldLatest = latest;
                 latest = newLatest;
                 logger.debug("watcher noticed updated file {}/{}{}: rev={}",
                              projectName, repositoryName, pathPattern, newLatest.revision());
                 notifyListeners();
                 if (oldLatest == null) {
                     initialValueFuture.complete(newLatest);
                 }
             }

             // Watch again for the next change.
             scheduleWatch(0);
         })
         .exceptionally(thrown -> {
             try {
                 final Throwable cause = thrown instanceof CompletionException ? thrown.getCause() : thrown;
                 boolean logged = false;
                 if (cause instanceof CentralDogmaException) {
                     if (cause instanceof EntryNotFoundException) {
                         logger.info("{}/{}{} does not exist yet; trying again",
                                     projectName, repositoryName, pathPattern);
                         logged = true;
                     } else if (cause instanceof RepositoryNotFoundException) {
                         logger.info("{}/{} does not exist yet; trying again",
                                     projectName, repositoryName);
                         logged = true;
                     } else if (cause instanceof ShuttingDownException) {
                         logger.info("Central Dogma is shutting down; trying again");
                         logged = true;
                     }
                 }

                 if (cause instanceof CancellationException) {
                     // Cancelled by close()
                     return null;
                 }

                 if (!logged) {
                     logger.warn("Failed to watch a file ({}/{}{}) at Central Dogma; trying again",
                                 projectName, repositoryName, pathPattern, cause);
                 }

                 scheduleWatch(numAttemptsSoFar + 1);
             } catch (Throwable t) {
                 logger.error("Unexpected exception while watching a file at Central Dogma:", t);
             }
             return null;
         });
    }

    protected abstract CompletableFuture<Latest<T>> doWatch(
            CentralDogma client, String projectName, String repositoryName, Revision lastKnownRevision);

    private void notifyListeners() {
        if (isStopped()) {
            // Do not notify after stopped.
            return;
        }

        final Latest<T> latest = this.latest;
        for (BiConsumer<? super Revision, ? super T> listener : updateListeners) {
            try {
                listener.accept(latest.revision(), latest.value());
            } catch (Exception e) {
                logger.warn("Exception thrown for watcher ({}/{}{}): rev={}",
                            projectName, repositoryName, pathPattern, latest.revision(), e);
            }
        }
    }

    private void handleEventLoopShutdown(RejectedExecutionException e) {
        if (logger.isTraceEnabled()) {
            logger.trace("Stopping to watch since the event loop is shut down:", e);
        } else {
            logger.debug("Stopping to watch since the event loop is shut down.");
        }

        close();
    }
}
