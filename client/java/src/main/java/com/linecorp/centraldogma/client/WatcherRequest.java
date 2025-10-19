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
package com.linecorp.centraldogma.client;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.linecorp.centraldogma.common.PathPattern;
import com.linecorp.centraldogma.common.Query;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Prepares to create a {@link Watcher}.
 */
public final class WatcherRequest<T> extends WatchOptions {

    private static final long DEFAULT_DELAY_ON_SUCCESS_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private static final long DEFAULT_MAX_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final double DEFAULT_MULTIPLIER = 2.0;
    private static final double DEFAULT_JITTER_RATE = 0.2;

    private final CentralDogmaRepository centralDogmaRepo;
    @Nullable
    private final Query<T> query;
    @Nullable
    private final PathPattern pathPattern;
    private final ScheduledExecutorService blockingTaskExecutor;

    @Nullable
    private Function<Object, ? extends T> mapper;
    private Executor executor;

    @Nullable
    private final MeterRegistry meterRegistry;

    private long delayOnSuccessMillis = DEFAULT_DELAY_ON_SUCCESS_MILLIS;
    private long initialDelayMillis = DEFAULT_DELAY_ON_SUCCESS_MILLIS * 2;
    private long maxDelayMillis = DEFAULT_MAX_DELAY_MILLIS;
    private double multiplier = DEFAULT_MULTIPLIER;
    private double jitterRate = DEFAULT_JITTER_RATE;

    WatcherRequest(CentralDogmaRepository centralDogmaRepo, Query<T> query,
                   ScheduledExecutorService blockingTaskExecutor, @Nullable MeterRegistry meterRegistry) {
        this(centralDogmaRepo, query, null, blockingTaskExecutor, meterRegistry);
    }

    WatcherRequest(CentralDogmaRepository centralDogmaRepo, PathPattern pathPattern,
                   ScheduledExecutorService blockingTaskExecutor, @Nullable MeterRegistry meterRegistry) {
        this(centralDogmaRepo, null, pathPattern, blockingTaskExecutor, meterRegistry);
    }

    private WatcherRequest(CentralDogmaRepository centralDogmaRepo, @Nullable Query<T> query,
                           @Nullable PathPattern pathPattern, ScheduledExecutorService blockingTaskExecutor,
                           @Nullable MeterRegistry meterRegistry) {
        this.centralDogmaRepo = centralDogmaRepo;
        this.query = query;
        this.pathPattern = pathPattern;
        this.blockingTaskExecutor = blockingTaskExecutor;
        executor = blockingTaskExecutor;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Sets the {@link Function} to apply to the result of a watch request.
     */
    @SuppressWarnings("unchecked")
    public <U> WatcherRequest<U> map(Function<? super T, ? extends U> mapper) {
        if (this.mapper == null) {
            this.mapper = (Function<Object, ? extends T>) mapper;
        } else {
            this.mapper = (Function<Object, ? extends T>) this.mapper.andThen(mapper);
        }
        return (WatcherRequest<U>) this;
    }

    /**
     * Sets the {@link Executor} to execute the {@link #map(Function)}.
     */
    public WatcherRequest<T> mapperExecutor(Executor executor) {
        this.executor = executor;
        return this;
    }

    /**
     * Sets the delay for sending the next watch request when the previous request succeeds.
     */
    public WatcherRequest<T> delayOnSuccess(Duration delayOnSuccess) {
        requireNonNull(delayOnSuccess, "delayOnSuccess");
        checkArgument(!delayOnSuccess.isNegative(),
                      "delayOnSuccess: %s (expected: >= 0)", delayOnSuccess);
        return delayOnSuccessMillis(delayOnSuccess.toMillis());
    }

    /**
     * Sets the delay in milliseconds for sending the next watch request when the previous request succeeds.
     */
    public WatcherRequest<T> delayOnSuccessMillis(long delayOnSuccessMillis) {
        this.delayOnSuccessMillis = delayOnSuccessMillis;
        checkArgument(delayOnSuccessMillis >= 0,
                      "delayOnSuccessMillis: %s (expected: >= 0)", delayOnSuccessMillis);
        return this;
    }

    /**
     * Sets the delays and multiplier which is used to calculate the delay
     * for sending the next watch request when the previous request fails.
     * Currently, it uses exponential backoff. File a feature request if you need another algorithm.
     */
    public WatcherRequest<T> backoffOnFailure(long initialDelayMillis, long maxDelayMillis,
                                              double multiplier) {
        checkArgument(initialDelayMillis >= 0, "initialDelayMillis: %s (expected: >= 0)", initialDelayMillis);
        checkArgument(initialDelayMillis <= maxDelayMillis, "maxDelayMillis: %s (expected: >= %s)",
                      maxDelayMillis, initialDelayMillis);
        checkArgument(multiplier > 1.0, "multiplier: %s (expected: > 1.0)", multiplier);
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.multiplier = multiplier;
        return this;
    }

    /**
     * Sets the jitter to apply the delay.
     */
    public WatcherRequest<T> jitterRate(double jitterRate) {
        checkArgument(0.0 <= jitterRate && jitterRate <= 1.0,
                      "jitterRate: %s (expected: >= 0.0 and <= 1.0)", jitterRate);
        this.jitterRate = jitterRate;
        return this;
    }

    @Override
    public WatcherRequest<T> timeout(Duration timeout) {
        //noinspection unchecked
        return (WatcherRequest<T>) super.timeout(timeout);
    }

    @Override
    public WatcherRequest<T> timeoutMillis(long timeoutMillis) {
        //noinspection unchecked
        return (WatcherRequest<T>) super.timeoutMillis(timeoutMillis);
    }

    @Override
    public WatcherRequest<T> errorOnEntryNotFound(boolean errorOnEntryNotFound) {
        //noinspection unchecked
        return (WatcherRequest<T>) super.errorOnEntryNotFound(errorOnEntryNotFound);
    }

    /**
     * Creates a new {@link Watcher} and starts to watch the target. The {@link Watcher} must be closed via
     * {@link Watcher#close()} after use.
     */
    public Watcher<T> start() {
        final String proName = centralDogmaRepo.projectName();
        final String repoName = centralDogmaRepo.repositoryName();
        final AbstractWatcher<T> watcher;
        if (query != null) {
            watcher = new FileWatcher<>(
                    centralDogmaRepo.centralDogma(), blockingTaskExecutor, proName, repoName, query,
                    timeoutMillis(), errorOnEntryNotFound(), mapper, executor, delayOnSuccessMillis,
                    initialDelayMillis, maxDelayMillis, multiplier, jitterRate, meterRegistry);
        } else {
            assert pathPattern != null;
            watcher = new FilesWatcher<>(
                    centralDogmaRepo.centralDogma(), blockingTaskExecutor, proName, repoName, pathPattern,
                    timeoutMillis(), errorOnEntryNotFound(), mapper, executor, delayOnSuccessMillis,
                    initialDelayMillis, maxDelayMillis, multiplier, jitterRate, meterRegistry);
        }
        watcher.start();
        return watcher;
    }
}
