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
package com.linecorp.centraldogma.client;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.math.LongMath.saturatedAdd;
import static java.util.Objects.requireNonNull;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.common.ShuttingDownException;

final class DefaultWatcher<T> implements Watcher<T> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultWatcher.class);

    private enum State {
        INIT,
        STARTED,
        STOPPED
    }

    private final ScheduledExecutorService watchScheduler;
    private final String projectName;
    private final String repositoryName;
    private final String pathPattern;
    private final Function<Revision, CompletableFuture<Latest<T>>> watchFunction;
    private final boolean errorOnEntryNotFound;
    private final WatcherOptions watcherOptions;

    private final List<Map.Entry<BiConsumer<? super Revision, ? super T>, Executor>> updateListeners =
            new CopyOnWriteArrayList<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.INIT);
    private final CompletableFuture<Latest<T>> initialValueFuture = new CompletableFuture<>();

    @Nullable
    private volatile Latest<T> latest;
    @Nullable
    private volatile ScheduledFuture<?> currentScheduleFuture;
    @Nullable
    private volatile CompletableFuture<?> currentWatchFuture;

    DefaultWatcher(ScheduledExecutorService watchScheduler,
                   String projectName, String repositoryName, String pathPattern,
                   Function<Revision, CompletableFuture<Latest<T>>> watchFunction,
                   boolean errorOnEntryNotFound, WatcherOptions watcherOptions) {
        this.watchScheduler = requireNonNull(watchScheduler, "watchScheduler");
        this.projectName = requireNonNull(projectName, "projectName");
        this.repositoryName = requireNonNull(repositoryName, "repositoryName");
        this.pathPattern = requireNonNull(pathPattern, "pathPattern");
        this.watchFunction = requireNonNull(watchFunction, "watchFunction");
        this.errorOnEntryNotFound = errorOnEntryNotFound;
        this.watcherOptions = requireNonNull(watcherOptions, "watcherOptions");
    }

    @Override
    public ScheduledExecutorService watchScheduler() {
        return watchScheduler;
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

    @Override
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
        watch(listener, watchScheduler);
    }

    @Override
    public void watch(BiConsumer<? super Revision, ? super T> listener, Executor executor) {
        requireNonNull(listener, "listener");
        checkState(!isStopped(), "watcher closed");
        updateListeners.add(new SimpleImmutableEntry<>(listener, executor));

        final Latest<T> latest = this.latest;
        if (latest != null) {
            // Perform initial notification so that the listener always gets the initial value.
            try {
                executor.execute(() -> {
                    listener.accept(latest.revision(), latest.value());
                });
            } catch (RejectedExecutionException e) {
                handleExecutorShutdown(executor, e);
            }
        }
    }

    private void scheduleWatch(int numAttemptsSoFar) {
        if (isStopped()) {
            return;
        }

        final long delay;
        if (numAttemptsSoFar == 0) {
            delay = latest != null ? watcherOptions.delayOnSuccessMillis() : 0;
        } else {
            delay = nextDelayMillis(numAttemptsSoFar);
        }

        try {
            currentScheduleFuture = watchScheduler.schedule(() -> {
                currentScheduleFuture = null;
                doWatch(numAttemptsSoFar);
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            handleExecutorShutdown(watchScheduler, e);
        }
    }

    private long nextDelayMillis(int numAttemptsSoFar) {
        final long nextDelayMillis;
        if (numAttemptsSoFar == 1) {
            nextDelayMillis = watcherOptions.initialDelayMillis();
        } else {
            nextDelayMillis =
                    Math.min(saturatedMultiply(watcherOptions.initialDelayMillis(),
                                               Math.pow(watcherOptions.multiplier(), numAttemptsSoFar - 1)),
                             watcherOptions.maxDelayMillis());
        }

        final long minJitter = (long) (nextDelayMillis * (1 - watcherOptions.jitterRate()));
        final long maxJitter = (long) (nextDelayMillis * (1 + watcherOptions.jitterRate()));
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

    private void doWatch(int numAttemptsSoFar) {
        if (isStopped()) {
            return;
        }

        final Latest<T> latest = this.latest;
        final Revision lastKnownRevision = latest != null ? latest.revision() : Revision.INIT;
        final CompletableFuture<Latest<T>> f = watchFunction.apply(lastKnownRevision);

        currentWatchFuture = f;
        f.whenComplete((result, cause) -> currentWatchFuture = null)
         .thenAccept(newLatest -> {
             if (newLatest != null) {
                 this.latest = newLatest;
                 logger.debug("watcher noticed updated file {}/{}{}: rev={}",
                              projectName, repositoryName, pathPattern, newLatest.revision());
                 notifyListeners();
                 if (!initialValueFuture.isDone()) {
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
                         if (!initialValueFuture.isDone() && errorOnEntryNotFound) {
                             initialValueFuture.completeExceptionally(thrown);
                             close();
                             return null;
                         }
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

    private void notifyListeners() {
        if (isStopped()) {
            // Do not notify after stopped.
            return;
        }

        final Latest<T> latest = this.latest;
        assert latest != null;
        for (Map.Entry<BiConsumer<? super Revision, ? super T>, Executor> entry : updateListeners) {
            final BiConsumer<? super Revision, ? super T> listener = entry.getKey();
            final Executor executor = entry.getValue();
            executor.execute(() -> {
                try {
                    listener.accept(latest.revision(), latest.value());
                } catch (Exception e) {
                    logger.warn("Exception thrown for watcher ({}/{}{}): rev={}",
                                projectName, repositoryName, pathPattern, latest.revision(), e);
                }
            });
        }
    }

    private void handleExecutorShutdown(Executor executor, RejectedExecutionException e) {
        if (logger.isTraceEnabled()) {
            logger.trace("Stopping to watch since the executor is shut down. executor: {}", executor, e);
        } else {
            logger.debug("Stopping to watch since the executor is shut down. executor: {}", executor);
        }

        close();
    }
}
