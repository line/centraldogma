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

import java.time.Instant;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.centraldogma.common.CentralDogmaException;
import com.linecorp.centraldogma.common.EntryNotFoundException;
import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.RepositoryNotFoundException;
import com.linecorp.centraldogma.common.Revision;

import io.micrometer.core.instrument.Meter.Id;
import io.micrometer.core.instrument.Meter.Type;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

abstract class AbstractWatcher<T> implements Watcher<T> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractWatcher.class);
    private static final String LATEST_REVISION_METER_NAME = "centraldogma.client.watcher.latest.revision";
    private static final String LATEST_RECEIVED_TIME_METER_NAME =
            "centraldogma.client.watcher.latest.received.time";

    private enum State {
        INIT,
        STARTED,
        STOPPED
    }

    private final ScheduledExecutorService watchScheduler;
    private final String projectName;
    private final String repositoryName;
    private final String pathPattern;
    private final boolean errorOnEntryNotFound;
    private final long delayOnSuccessMillis;
    private final long initialDelayMillis;
    private final long maxDelayMillis;
    private final double multiplier;
    private final double jitterRate;
    @Nullable
    private final MeterRegistry meterRegistry;
    private final Tags tags;

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

    // unix epoch seconds
    // In Prometheus, it is common to handle data with second precision, so we intentionally use second precision.
    // ref: https://prometheus.io/docs/prometheus/latest/querying/functions/#time
    private final AtomicLong latestReceivedTime = new AtomicLong();

    AbstractWatcher(ScheduledExecutorService watchScheduler, String projectName, String repositoryName,
                    String pathPattern, boolean errorOnEntryNotFound, long delayOnSuccessMillis,
                    long initialDelayMillis, long maxDelayMillis, double multiplier, double jitterRate,
                    @Nullable MeterRegistry meterRegistry) {
        this.watchScheduler = watchScheduler;
        this.projectName = projectName;
        this.repositoryName = repositoryName;
        this.pathPattern = pathPattern;
        this.errorOnEntryNotFound = errorOnEntryNotFound;
        this.delayOnSuccessMillis = delayOnSuccessMillis;
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.multiplier = multiplier;
        this.jitterRate = jitterRate;
        this.meterRegistry = meterRegistry;
        tags = Tags.of(
                "project", projectName,
                "repository", repositoryName,
                "pathPattern", pathPattern,
                // There is a possibility of using the same watcher for the same project, repo, and pathPattern.
                // Therefore, the watcher’s hash code should be included as a tag.
                "watcher_hash", String.valueOf(System.identityHashCode(this))
        );
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

    /**
     * Starts to watch the file specified in the {@link Query} or the {@link PathPattern}
     * given with the constructor.
     */
    void start() {
        if (state.compareAndSet(State.INIT, State.STARTED)) {
            scheduleWatch(0);
        }
        if (meterRegistry != null) {
            // emit metrics once the values are ready
            initialValueFuture.thenAccept(latest -> {
                meterRegistry.gauge(LATEST_REVISION_METER_NAME, tags, this,
                                    watcher -> watcher.latest().revision().major());
            });
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

        if (meterRegistry != null) {
            meterRegistry.remove(new Id(LATEST_REVISION_METER_NAME, tags, null, null, Type.GAUGE));
            meterRegistry.remove(
                    new Id(LATEST_RECEIVED_TIME_METER_NAME, tags, null, null, Type.GAUGE));
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
            // There's a chance that listener.accept(...) is called twice for the same value
            // if this watch method is called:
            // - after " this.latest = newLatest;" is invoked.
            // - and before notifyListener() is called.
            // However, it's such a rare case and we usually call `watch` method right after creating a Watcher,
            // which means latest is probably not set yet, so we don't use a lock to guarantee
            // the atomicity.
            executor.execute(() -> listener.accept(latest.revision(), latest.value()));
        }
    }

    private void scheduleWatch(int numAttemptsSoFar) {
        if (isStopped()) {
            return;
        }

        final long delay;
        if (numAttemptsSoFar == 0) {
            delay = latest != null ? delayOnSuccessMillis : 0;
        } else {
            delay = nextDelayMillis(numAttemptsSoFar);
        }

        currentScheduleFuture = watchScheduler.schedule(() -> {
            currentScheduleFuture = null;
            doWatch(numAttemptsSoFar);
        }, delay, TimeUnit.MILLISECONDS);
    }

    private long nextDelayMillis(int numAttemptsSoFar) {
        final long nextDelayMillis;
        if (numAttemptsSoFar == 1) {
            nextDelayMillis = initialDelayMillis;
        } else {
            nextDelayMillis =
                    Math.min(saturatedMultiply(initialDelayMillis, Math.pow(multiplier, numAttemptsSoFar - 1)),
                             maxDelayMillis);
        }

        final long minJitter = (long) (nextDelayMillis * (1 - jitterRate));
        final long maxJitter = (long) (nextDelayMillis * (1 + jitterRate));
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
        final CompletableFuture<Latest<T>> f = doWatch(lastKnownRevision);

        currentWatchFuture = f;
        f.thenAccept(newLatest -> {
             currentWatchFuture = null;
             if (newLatest != null) {
                 this.latest = newLatest;
                 logger.debug("watcher noticed updated file {}/{}{}: rev={}",
                              projectName, repositoryName, pathPattern, newLatest.revision());
                 notifyListeners(newLatest);
                 updateLatestReceivedTime();
                 if (!initialValueFuture.isDone()) {
                     initialValueFuture.complete(newLatest);
                 }
             }

             // Watch again for the next change.
             scheduleWatch(0);
         })
         .exceptionally(thrown -> {
             currentWatchFuture = null;
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

    abstract CompletableFuture<Latest<T>> doWatch(Revision lastKnownRevision);

    private void notifyListeners(Latest<T> latest) {
        if (isStopped()) {
            // Do not notify after stopped.
            return;
        }

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

    private void updateLatestReceivedTime() {
        if (meterRegistry == null) {
            return;
        }

        if (latestReceivedTime.getAndSet(Instant.now().getEpochSecond()) == 0) {
            // emit metrics once the values are ready
            meterRegistry.gauge(LATEST_RECEIVED_TIME_METER_NAME, tags, latestReceivedTime, AtomicLong::get);
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("watchScheduler", watchScheduler)
                          .add("projectName", projectName)
                          .add("repositoryName", repositoryName)
                          .add("pathPattern", pathPattern)
                          .add("errorOnEntryNotFound", errorOnEntryNotFound)
                          .add("delayOnSuccessMillis", delayOnSuccessMillis)
                          .add("initialDelayMillis", initialDelayMillis)
                          .add("maxDelayMillis", maxDelayMillis)
                          .add("multiplier", multiplier)
                          .add("jitterRate", jitterRate)
                          .add("latest", latest)
                          .toString();
    }
}
