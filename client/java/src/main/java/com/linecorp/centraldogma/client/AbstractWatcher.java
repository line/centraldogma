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
package com.linecorp.centraldogma.client;

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.internal.thrift.CentralDogmaException;

abstract class AbstractWatcher<T> implements Watcher<T> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractWatcher.class);

    private static final long DELAY_ON_SUCCESS_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private static final long MIN_INTERVAL_MILLIS = DELAY_ON_SUCCESS_MILLIS * 2;
    private static final long MAX_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final double JITTER_RATE = 0.2;
    private static final long WATCH_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final Supplier<? extends Backoff> BACKOFF_SUPPLIER =
            () -> Backoff.exponential(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS).withJitter(JITTER_RATE);

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
     * Starts to watch the file specified in the {@link Query} given with the constructor.
     */
    void start() {
        if (state.compareAndSet(State.INIT, State.STARTED)) {
            scheduleWatch(null, 0);
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

    private void scheduleWatch(Backoff backoff, int numAttemptsSoFar) {
        if (isStopped()) {
            return;
        }

        final long delay;
        if (numAttemptsSoFar == 0) {
            assert backoff == null;
            delay = latest != null ? DELAY_ON_SUCCESS_MILLIS : 0;
        } else {
            backoff = BACKOFF_SUPPLIER.get();
            delay = backoff.nextDelayMillis(numAttemptsSoFar);
        }

        final Backoff finalBackoff = backoff;
        try {
            currentScheduleFuture = executor.schedule(() -> {
                currentScheduleFuture = null;
                doWatch(finalBackoff, numAttemptsSoFar);
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            handleEventLoopShutdown(e);
        }
    }

    private void doWatch(Backoff backoff, int numAttemptsSoFar) {
        if (isStopped()) {
            return;
        }

        final Revision lastKnownRevision = latest != null ? latest.revision() : Revision.INIT;
        final CompletableFuture<Latest<T>> f = doWatch(client, projectName, repositoryName, lastKnownRevision,
                                                       WATCH_TIMEOUT_MILLIS);

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
             scheduleWatch(null, 0);
         })
         .exceptionally(voidFunction(thrown -> {
             final Throwable cause = thrown instanceof CompletionException ? thrown.getCause() : thrown;
             boolean logged = false;
             if (cause instanceof CentralDogmaException) {
                 switch (((CentralDogmaException) cause).getErrorCode()) {
                     case ENTRY_NOT_FOUND:
                         logger.info("{}/{}{} does not exist yet; trying again",
                                     projectName, repositoryName, pathPattern);
                         logged = true;
                         break;
                     case REPOSITORY_NOT_FOUND:
                         logger.info("{}/{} does not exist yet; trying again",
                                     projectName, repositoryName);
                         logged = true;
                         break;
                     case SHUTTING_DOWN:
                         logger.info("Central Dogma is shutting down; trying again");
                         logged = true;
                         break;
                 }
             }

             if (cause instanceof CancellationException) {
                 // Cancelled by close()
                 return;
             }

             if (!logged) {
                 logger.warn("Failed to watch a file ({}/{}{}); trying again",
                             projectName, repositoryName, pathPattern, cause);
             }

             scheduleWatch(backoff, numAttemptsSoFar + 1);
         }))
         .exceptionally(voidFunction(cause -> logger.error(
                 "Unexpected exception while watching a file:", cause)));
    }

    protected abstract CompletableFuture<Latest<T>> doWatch(
            CentralDogma client, String projectName, String repositoryName,
            Revision lastKnownRevision, long timeoutMillis);

    private void notifyListeners() {
        if (isStopped()) {
            // Do not notify after stopped.
            return;
        }

        final Latest<T> latest = this.latest;
        for (BiConsumer<? super Revision, ? super T> listener : updateListeners) {
            listener.accept(latest.revision(), latest.value());
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
